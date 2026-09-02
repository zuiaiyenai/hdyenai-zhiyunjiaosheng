package com.a09.tts.task;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AsyncTaskService {
    private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);
    private static final String RESTART_REASON = "应用重启导致任务中断";
    private final TaskRepository repository;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService scheduler;
    private final Duration timeout;
    private final int perUserConcurrency;
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();
    private final Map<String, String> reservations = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> activeByOwner = new ConcurrentHashMap<>();
    private final Map<String, String> activeDeduplication = new ConcurrentHashMap<>();
    private final Object[] taskLocks = new Object[64];

    public AsyncTaskService(
            TaskRepository repository,
            @Value("${app.tasks.core-pool-size:2}") int corePoolSize,
            @Value("${app.tasks.max-pool-size:4}") int maxPoolSize,
            @Value("${app.tasks.queue-capacity:20}") int queueCapacity,
            @Value("${app.tasks.timeout:15m}") Duration timeout,
            @Value("${app.tasks.per-user-concurrency:2}") int perUserConcurrency) {
        if (corePoolSize < 1 || maxPoolSize < corePoolSize || queueCapacity < 1
                || perUserConcurrency < 1 || timeout == null
                || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("异步任务线程池配置无效");
        }
        this.repository = repository;
        this.timeout = timeout;
        this.perUserConcurrency = perUserConcurrency;
        for (int index = 0; index < taskLocks.length; index++) {
            taskLocks[index] = new Object();
        }
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
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "media-task-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    public void recoverInterruptedTasks() {
        repository.markInterruptedTasksFailed(Instant.now(), RESTART_REASON);
    }

    public TaskSubmission submit(String owner, String type, String deduplicationKey,
                                 TaskAction action) {
        String normalizedOwner = normalizeOwner(owner);
        String normalizedType = requireValue(type, "任务类型不能为空");
        if (action == null) {
            throw new IllegalArgumentException("任务操作不能为空");
        }
        String dedupKey = normalizeDeduplicationKey(deduplicationKey);
        String dedupToken = dedupKey == null
                ? null : normalizedOwner + "\u0000" + normalizedType + "\u0000" + dedupKey;
        if (dedupToken != null) {
            String existingId = activeDeduplication.get(dedupToken);
            if (existingId != null) {
                TaskRecord existing = repository.findByIdAndOwner(existingId, normalizedOwner)
                        .orElse(null);
                if (existing != null && !existing.status().terminal()) {
                    return new TaskSubmission(existing.id(), true);
                }
                activeDeduplication.remove(dedupToken, existingId);
            }
        }

        reserveUser(normalizedOwner);
        String id = UUID.randomUUID().toString();
        reservations.put(id, normalizedOwner);
        if (dedupToken != null) {
            String existing = activeDeduplication.putIfAbsent(dedupToken, id);
            if (existing != null) {
                release(id, dedupToken);
                TaskRecord task = repository.findByIdAndOwner(existing, normalizedOwner)
                        .orElse(null);
                if (task != null && !task.status().terminal()) {
                    return new TaskSubmission(existing, true);
                }
                return submit(owner, type, deduplicationKey, action);
            }
        }

        Instant createdAt = Instant.now();
        repository.save(new TaskRecord(id, normalizedOwner, normalizedType, TaskStatus.PENDING,
                0, null, null, dedupKey, createdAt, null, null));
        String finalDedupToken = dedupToken;
        FutureTask<Void> future = new FutureTask<>(() -> {
            execute(id, action, finalDedupToken);
            return null;
        });
        futures.put(id, future);
        try {
            ScheduledFuture<?> timeoutFuture = scheduler.schedule(
                    () -> timeout(id, finalDedupToken), timeout.toMillis(), TimeUnit.MILLISECONDS);
            timeouts.put(id, timeoutFuture);
            executor.execute(future);
            return new TaskSubmission(id, false);
        } catch (RejectedExecutionException exception) {
            futures.remove(id);
            failBeforeStart(id, "任务队列已满");
            release(id, finalDedupToken);
            throw new TaskCapacityException("任务队列已满，请稍后重试");
        }
    }

    public TaskRecord get(String id, String owner) {
        return repository.findByIdAndOwner(id, normalizeOwner(owner))
                .orElseThrow(() -> new IllegalArgumentException("任务不存在或无权访问"));
    }

    public TaskRecord cancel(String id, String owner) {
        String normalizedOwner = normalizeOwner(owner);
        synchronized (lock(id)) {
            TaskRecord current = repository.findByIdAndOwner(id, normalizedOwner)
                    .orElseThrow(() -> new IllegalArgumentException("任务不存在或无权访问"));
            if (current.status().terminal()) {
                return current;
            }
            TaskRecord cancelled = update(current, TaskStatus.CANCELLED, current.progress(),
                    current.resultData(), "用户取消任务", current.startedAt(), Instant.now());
            repository.save(cancelled);
            Future<?> future = futures.get(id);
            if (future != null) {
                future.cancel(true);
            }
            release(id, dedupToken(current));
            return cancelled;
        }
    }

    private void execute(String id, TaskAction action, String dedupToken) {
        try {
            synchronized (lock(id)) {
                TaskRecord pending = repository.findById(id).orElseThrow();
                if (pending.status().terminal()) {
                    return;
                }
                repository.save(update(pending, TaskStatus.RUNNING, 10,
                        null, null, Instant.now(), null));
            }
            String result = action.execute();
            synchronized (lock(id)) {
                TaskRecord running = repository.findById(id).orElseThrow();
                if (!running.status().terminal()) {
                    repository.save(update(running, TaskStatus.SUCCESS, 100,
                            result, null, running.startedAt(), Instant.now()));
                }
            }
        } catch (Exception exception) {
            boolean failed = false;
            synchronized (lock(id)) {
                TaskRecord current = repository.findById(id).orElse(null);
                if (current != null && !current.status().terminal()) {
                    repository.save(update(current, TaskStatus.FAILED, current.progress(),
                            null, safeMessage(exception), current.startedAt(), Instant.now()));
                    failed = true;
                }
            }
            if (failed) {
                log.error("异步任务执行失败: taskId={}", id, exception);
            }
        } finally {
            release(id, dedupToken);
        }
    }

    private void timeout(String id, String dedupToken) {
        synchronized (lock(id)) {
            TaskRecord current = repository.findById(id).orElse(null);
            if (current == null || current.status().terminal()) {
                return;
            }
            repository.save(update(current, TaskStatus.TIMEOUT, current.progress(),
                    null, "任务执行超时", current.startedAt(), Instant.now()));
            Future<?> future = futures.get(id);
            if (future != null) {
                future.cancel(true);
            }
            release(id, dedupToken);
        }
    }

    private void failBeforeStart(String id, String message) {
        TaskRecord current = repository.findById(id).orElseThrow();
        repository.save(update(current, TaskStatus.FAILED, 0,
                null, message, null, Instant.now()));
    }

    private TaskRecord update(TaskRecord task, TaskStatus status, int progress,
                              String resultData, String errorMessage,
                              Instant startedAt, Instant finishedAt) {
        return new TaskRecord(task.id(), task.owner(), task.type(), status, progress,
                resultData, errorMessage, task.deduplicationKey(), task.createdAt(),
                startedAt, finishedAt);
    }

    private void reserveUser(String owner) {
        AtomicInteger counter = activeByOwner.computeIfAbsent(owner, ignored -> new AtomicInteger());
        int active = counter.incrementAndGet();
        if (active > perUserConcurrency) {
            counter.decrementAndGet();
            throw new TaskCapacityException("当前用户运行中的任务过多，请稍后重试");
        }
    }

    private void release(String id, String dedupToken) {
        String owner = reservations.remove(id);
        if (owner != null) {
            AtomicInteger counter = activeByOwner.get(owner);
            if (counter != null && counter.decrementAndGet() <= 0) {
                activeByOwner.remove(owner, counter);
            }
        }
        futures.remove(id);
        ScheduledFuture<?> timeoutFuture = timeouts.remove(id);
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
        if (dedupToken != null) {
            activeDeduplication.remove(dedupToken, id);
        }
    }

    private String dedupToken(TaskRecord task) {
        return task.deduplicationKey() == null ? null
                : task.owner() + "\u0000" + task.type() + "\u0000" + task.deduplicationKey();
    }

    private Object lock(String id) {
        return taskLocks[Math.floorMod(id.hashCode(), taskLocks.length)];
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
