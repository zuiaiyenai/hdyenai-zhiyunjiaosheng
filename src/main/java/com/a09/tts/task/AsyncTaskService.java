package com.a09.tts.task;

import com.a09.tts.api.PageResult;
import com.a09.tts.api.Pagination;
import com.a09.tts.api.ResourceNotFoundException;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AsyncTaskService {
    private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);
    private final TaskRepository repository;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService scheduler;
    private final Duration timeout;
    private final int perUserConcurrency;
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();

    @Autowired
    public AsyncTaskService(
            TaskRepository repository,
            @Value("${app.tasks.core-pool-size:2}") int corePoolSize,
            @Value("${app.tasks.max-pool-size:4}") int maxPoolSize,
            @Value("${app.tasks.queue-capacity:20}") int queueCapacity,
            @Value("${app.tasks.timeout:15m}") Duration timeout,
            @Value("${app.tasks.per-user-concurrency:2}") int perUserConcurrency,
            MeterRegistry meterRegistry) {
        if (corePoolSize < 1 || maxPoolSize < corePoolSize || queueCapacity < 1
                || perUserConcurrency < 1 || timeout == null
                || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("异步任务线程池配置无效");
        }
        this.repository = repository;
        this.timeout = timeout;
        this.perUserConcurrency = perUserConcurrency;
        AtomicInteger workerNumber = new AtomicInteger();
        ThreadFactory workerFactory = runnable -> {
            Thread thread = new Thread(runnable, "media-task-" + workerNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
                corePoolSize, maxPoolSize, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), workerFactory,
                new ThreadPoolExecutor.AbortPolicy());
        ExecutorServiceMetrics.monitor(
                meterRegistry, executor, "fctts.async.tasks", Tags.empty());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "media-task-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    public TaskSubmission submit(String owner, String type, String deduplicationKey,
                                 TaskAction action) {
        return submit(owner, type, deduplicationKey, action, () -> { });
    }

    public TaskSubmission submit(String owner, String type, String deduplicationKey,
                                 TaskAction action, Runnable completion) {
        String normalizedOwner = normalizeOwner(owner);
        String normalizedType = requireValue(type, "任务类型不能为空");
        if (action == null) {
            throw new IllegalArgumentException("任务操作不能为空");
        }
        if (completion == null) {
            throw new IllegalArgumentException("任务清理操作不能为空");
        }
        String dedupKey = normalizeDeduplicationKey(deduplicationKey);
        String id = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        TaskRecord task = new TaskRecord(id, normalizedOwner, normalizedType, TaskStatus.PENDING,
                0, null, null, dedupKey, createdAt, null, null);
        TaskRepository.CreateResult createResult = repository.create(task, perUserConcurrency);
        if (createResult.disposition() == TaskRepository.CreateDisposition.DUPLICATE) {
            return new TaskSubmission(createResult.task().id(), true);
        }
        if (createResult.disposition() == TaskRepository.CreateDisposition.CAPACITY_EXCEEDED) {
            throw new TaskCapacityException("当前用户运行中的任务过多，请稍后重试");
        }

        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean cleaned = new AtomicBoolean();
        Runnable cleanupOnce = () -> {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            try {
                completion.run();
            } catch (RuntimeException exception) {
                log.warn("异步任务完成后的资源清理失败: taskId={}", id, exception);
            }
        };
        FutureTask<Void> future = new FutureTask<>(() -> {
            execute(id, action);
            return null;
        }) {
            @Override
            public void run() {
                started.set(true);
                try {
                    super.run();
                } finally {
                    cleanupOnce.run();
                }
            }

            @Override
            protected void done() {
                if (!started.get()) {
                    cleanupOnce.run();
                }
            }
        };
        futures.put(id, future);
        try {
            ScheduledFuture<?> timeoutFuture = scheduler.schedule(
                    () -> timeout(id), timeout.toMillis(), TimeUnit.MILLISECONDS);
            timeouts.put(id, timeoutFuture);
            executor.execute(future);
            return new TaskSubmission(id, false);
        } catch (RejectedExecutionException exception) {
            failBeforeStart(id, "任务队列已满");
            future.cancel(false);
            clearLocalTracking(id);
            throw new TaskCapacityException("任务队列已满，请稍后重试");
        }
    }

    public TaskRecord get(String id, String owner) {
        return repository.findByIdAndOwner(id, normalizeOwner(owner))
                .orElseThrow(() -> new ResourceNotFoundException("任务不存在或无权访问"));
    }

    public PageResult<TaskRecord> list(String owner, Integer pageValue, Integer sizeValue) {
        int page = Pagination.page(pageValue);
        int size = Pagination.size(sizeValue);
        return PageResult.fromWindow(repository.findByOwner(
                normalizeOwner(owner), Pagination.offset(page, size), size + 1), page, size);
    }

    public TaskRecord cancel(String id, String owner) {
        String normalizedOwner = normalizeOwner(owner);
        TaskRecord current = repository.findByIdAndOwner(id, normalizedOwner)
                .orElseThrow(() -> new ResourceNotFoundException("任务不存在或无权访问"));
        if (current.status().terminal()) {
            return current;
        }
        if (repository.markCancelled(id, normalizedOwner, "用户取消任务", Instant.now())) {
            Future<?> future = futures.get(id);
            if (future != null) {
                future.cancel(true);
            }
            clearLocalTracking(id);
        }
        return repository.findByIdAndOwner(id, normalizedOwner)
                .orElseThrow(() -> new ResourceNotFoundException("任务不存在或无权访问"));
    }

    private void execute(String id, TaskAction action) {
        try {
            if (!repository.markRunning(id, Instant.now())) {
                return;
            }
            String result = action.execute();
            repository.markSucceeded(id, result, Instant.now());
        } catch (Exception exception) {
            if (repository.markFailed(id, safeMessage(exception), Instant.now())) {
                log.error("异步任务执行失败: taskId={}", id, exception);
            }
        } finally {
            clearLocalTracking(id);
        }
    }

    private void timeout(String id) {
        if (repository.markTimedOut(id, "任务执行超时", Instant.now())) {
            Future<?> future = futures.get(id);
            if (future != null) {
                future.cancel(true);
            }
            clearLocalTracking(id);
        }
    }

    private void failBeforeStart(String id, String message) {
        repository.markFailed(id, message, Instant.now());
    }

    private void clearLocalTracking(String id) {
        futures.remove(id);
        ScheduledFuture<?> timeoutFuture = timeouts.remove(id);
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
    }

    private String normalizeOwner(String owner) {
        return owner == null || owner.isBlank() ? "anonymous" : owner;
    }

    private String requireValue(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String normalizeDeduplicationKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String key = value.trim();
        if (key.length() > 255) {
            throw new IllegalArgumentException("任务幂等键过长");
        }
        return key;
    }

    private String safeMessage(Exception exception) {
        return "任务执行失败";
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        scheduler.shutdownNow();
    }

    @FunctionalInterface
    public interface TaskAction {
        String execute() throws Exception;
    }

    public record TaskSubmission(String taskId, boolean duplicate) {
    }

    public static class TaskCapacityException extends RuntimeException {
        public TaskCapacityException(String message) {
            super(message);
        }
    }
}
