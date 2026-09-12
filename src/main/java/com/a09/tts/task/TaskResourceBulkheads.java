package com.a09.tts.task;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TaskResourceBulkheads {
    private final EnumMap<TaskResource, Limiter> limiters = new EnumMap<>(TaskResource.class);
    private final Duration acquireTimeout;
    private final boolean unrestricted;
    private final ThreadLocal<EnumMap<TaskResource, Integer>> held =
            ThreadLocal.withInitial(() -> new EnumMap<>(TaskResource.class));

    @Autowired
    public TaskResourceBulkheads(
            @Value("${app.resources.tts.max-concurrent:1}") int tts,
            @Value("${app.resources.asr.max-concurrent:1}") int asr,
            @Value("${app.resources.ffmpeg.max-concurrent:1}") int ffmpeg,
            @Value("${app.resources.courseware.max-concurrent:1}") int courseware,
            @Value("${app.resources.acquire-timeout:50ms}") Duration acquireTimeout,
            MeterRegistry registry) {
        this(Map.of(TaskResource.TTS, tts, TaskResource.ASR, asr,
                        TaskResource.FFMPEG, ffmpeg, TaskResource.COURSEWARE, courseware),
                acquireTimeout, registry, false);
    }

    public TaskResourceBulkheads(
            Map<TaskResource, Integer> limits, Duration acquireTimeout, MeterRegistry registry) {
        this(limits, acquireTimeout, registry, false);
    }

    private TaskResourceBulkheads(
            Map<TaskResource, Integer> limits, Duration acquireTimeout,
            MeterRegistry registry, boolean unrestricted) {
        if (acquireTimeout == null || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("资源等待时间不能为负数");
        }
        this.acquireTimeout = acquireTimeout;
        this.unrestricted = unrestricted;
        for (TaskResource resource : TaskResource.values()) {
            int maximum = limits.getOrDefault(resource, 0);
            if (maximum < 1) {
                throw new IllegalArgumentException(resource + " 最大并发必须大于 0");
            }
            Limiter limiter = new Limiter(maximum,
                    Counter.builder("fctts.resource.bulkhead.rejected")
                            .tag("resource", resource.name().toLowerCase())
                            .register(registry));
            limiters.put(resource, limiter);
            Gauge.builder("fctts.resource.bulkhead.active", limiter.active, AtomicInteger::get)
                    .tag("resource", resource.name().toLowerCase())
                    .register(registry);
            Gauge.builder("fctts.resource.bulkhead.max", () -> maximum)
                    .tag("resource", resource.name().toLowerCase())
                    .register(registry);
        }
    }

    public static TaskResourceBulkheads unrestricted() {
        return new TaskResourceBulkheads(
                Map.of(TaskResource.TTS, 1, TaskResource.ASR, 1,
                        TaskResource.FFMPEG, 1, TaskResource.COURSEWARE, 1),
                Duration.ZERO, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), true);
    }

    public Permit acquire(TaskResource resource) {
        return acquire(Set.of(resource));
    }

    public Permit acquire(Set<TaskResource> resources) {
        if (resources == null || resources.isEmpty() || unrestricted) {
            return new Permit(this, List.of());
        }
        List<TaskResource> ordered = resources.stream().sorted().toList();
        List<TaskResource> acquired = new ArrayList<>();
        try {
            for (TaskResource resource : ordered) {
                acquireOne(resource);
                acquired.add(resource);
            }
            return new Permit(this, acquired);
        } catch (RuntimeException exception) {
            for (int index = acquired.size() - 1; index >= 0; index--) {
                releaseOne(acquired.get(index));
            }
            throw exception;
        }
    }

    private void acquireOne(TaskResource resource) {
        EnumMap<TaskResource, Integer> current = held.get();
        Integer count = current.get(resource);
        if (count != null) {
            current.put(resource, count + 1);
            return;
        }
        Limiter limiter = limiters.get(resource);
        try {
            if (!limiter.semaphore.tryAcquire(
                    acquireTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                limiter.rejected.increment();
                throw new ResourceCapacityException(resource + " 资源并发已满");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResourceCapacityException(resource + " 资源等待被中断", exception);
        }
        limiter.active.incrementAndGet();
        current.put(resource, 1);
    }

    private void releaseOne(TaskResource resource) {
        EnumMap<TaskResource, Integer> current = held.get();
        Integer count = current.get(resource);
        if (count == null) {
            return;
        }
        if (count > 1) {
            current.put(resource, count - 1);
            return;
        }
        current.remove(resource);
        Limiter limiter = limiters.get(resource);
        limiter.active.decrementAndGet();
        limiter.semaphore.release();
        if (current.isEmpty()) {
            held.remove();
        }
    }

    private static final class Limiter {
        private final Semaphore semaphore;
        private final AtomicInteger active = new AtomicInteger();
        private final Counter rejected;

        private Limiter(int maximum, Counter rejected) {
            this.semaphore = new Semaphore(maximum, true);
            this.rejected = rejected;
        }
    }

    public static final class Permit implements AutoCloseable {
        private final TaskResourceBulkheads owner;
        private final List<TaskResource> resources;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(TaskResourceBulkheads owner, List<TaskResource> resources) {
            this.owner = owner;
            this.resources = resources;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (int index = resources.size() - 1; index >= 0; index--) {
                owner.releaseOne(resources.get(index));
            }
        }
    }
}
