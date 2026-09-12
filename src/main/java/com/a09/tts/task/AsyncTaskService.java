package com.a09.tts.task;

import com.a09.tts.api.PageResult;
import com.a09.tts.api.Pagination;
import com.a09.tts.api.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.SynchronousQueue;

@Service
public class AsyncTaskService {
    private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

    private final TaskRepository repository;
    private final TaskDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService scheduler;
    private final Duration pollInterval;
    private final Duration timeout;
    private final Duration heartbeatInterval;
    private final Duration staleAfter;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final Duration shutdownGrace;
    private final int perUserConcurrency;
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();

    @Autowired
    public AsyncTaskService(
            TaskRepository repository,
            TaskDispatcher dispatcher,
            ObjectMapper objectMapper,
            @Value("${app.tasks.worker-count:2}") int workerCount,
            @Value("${app.tasks.poll-interval:250ms}") Duration pollInterval,
            @Value("${app.tasks.timeout:15m}") Duration timeout,
            @Value("${app.tasks.heartbeat-interval:10s}") Duration heartbeatInterval,
            @Value("${app.tasks.stale-after:45s}") Duration staleAfter,
            @Value("${app.tasks.recovery-interval:15s}") Duration recoveryInterval,
            @Value("${app.tasks.retry-base-delay:2s}") Duration retryBaseDelay,
            @Value("${app.tasks.retry-max-delay:1m}") Duration retryMaxDelay,
            @Value("${app.tasks.shutdown-grace:10s}") Duration shutdownGrace,
            @Value("${app.tasks.per-user-concurrency:2}") int perUserConcurrency,
            MeterRegistry meterRegistry) {
        validate(workerCount, pollInterval, timeout, heartbeatInterval, staleAfter,
                recoveryInterval, retryBaseDelay, retryMaxDelay, shutdownGrace,
                perUserConcurrency);
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.pollInterval = pollInterval;
        this.timeout = timeout;
        this.heartbeatInterval = heartbeatInterval;
        this.staleAfter = staleAfter;
        this.retryBaseDelay = retryBaseDelay;
        this.retryMaxDelay = retryMaxDelay;
        this.shutdownGrace = shutdownGrace;
        this.perUserConcurrency = perUserConcurrency;

        AtomicInteger workerNumber = new AtomicInteger();
        ThreadFactory workerFactory = runnable -> daemon(
                runnable, "db-task-worker-" + workerNumber.incrementAndGet());
        this.workers = new ThreadPoolExecutor(
                workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), workerFactory);
        ExecutorServiceMetrics.monitor(meterRegistry, workers,
                "fctts.async.tasks", java.util.List.<Tag>of());
        AtomicInteger schedulerNumber = new AtomicInteger();
        this.scheduler = java.util.concurrent.Executors.newScheduledThreadPool(2,
                runnable -> daemon(runnable,
                        "db-task-coordinator-" + schedulerNumber.incrementAndGet()));

        for (int index = 1; index <= workerCount; index++) {
            int workerIndex = index;
            workers.execute(() -> workerLoop(workerIndex));
        }
        scheduler.scheduleWithFixedDelay(this::recoverStaleSafely,
                0, recoveryInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public TaskSubmission submit(
            String owner, String type, String deduplicationKey,
            Object payload, int maxAttempts) {
        if (!accepting.get()) {
            throw new TaskCapacityException("任务服务正在关闭，请稍后重试");
        }
        String normalizedOwner = normalizeOwner(owner);
        String normalizedType = requireValue(type, "任务类型不能为空");
        if (payload == null) {
            throw new IllegalArgumentException("任务载荷不能为空");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("任务最大尝试次数必须在 1 到 10 之间");
        }
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("任务载荷无法序列化", exception);
        }
        if (payloadJson.length() > 1_000_000) {
            throw new IllegalArgumentException("任务载荷过大");
        }
        String dedupKey = normalizeDeduplicationKey(deduplicationKey);
        String id = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        TaskRecord task = new TaskRecord(id, normalizedOwner, normalizedType,
                TaskStatus.PENDING, 0, payloadJson, null, null, null, dedupKey,
                0, maxAttempts, createdAt, createdAt, null, null, null, null, 0);
        TaskRepository.CreateResult created = repository.create(task, perUserConcurrency);
        if (created.disposition() == TaskRepository.CreateDisposition.DUPLICATE) {
            return new TaskSubmission(created.task().id(), true);
        }
        if (created.disposition() == TaskRepository.CreateDisposition.CAPACITY_EXCEEDED) {
            throw new TaskCapacityException("当前用户活动任务过多，请稍后重试");
        }
        return new TaskSubmission(id, false);
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
            Thread worker = runningThreads.get(id);
            if (worker != null) {
                worker.interrupt();
            } else if (current.status() == TaskStatus.PENDING) {
                dispatcher.cleanup(repository.findById(id).orElse(current));
            }
        }
        return repository.findByIdAndOwner(id, normalizedOwner)
                .orElseThrow(() -> new ResourceNotFoundException("任务不存在或无权访问"));
    }

    private void workerLoop(int workerIndex) {
        while (accepting.get() && !Thread.currentThread().isInterrupted()) {
            String claimToken = instanceId + ":" + workerIndex + ":" + UUID.randomUUID();
            try {
                TaskRecord task = repository.claimNext(claimToken, Instant.now()).orElse(null);
                if (task == null) {
                    TimeUnit.MILLISECONDS.sleep(pollInterval.toMillis());
                    continue;
                }
                if (!accepting.get() || Thread.currentThread().isInterrupted()) {
                    repository.releaseClaim(task.id(), task.workerId(), "WORKER_SHUTDOWN",
                            "worker 关闭前释放任务", Instant.now());
                    break;
                }
                executeClaimed(task);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException exception) {
                log.error("DB worker 循环失败: worker={}", workerIndex, exception);
                try {
                    TimeUnit.MILLISECONDS.sleep(pollInterval.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void executeClaimed(TaskRecord task) {
        Thread thread = Thread.currentThread();
        runningThreads.put(task.id(), thread);
        ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(
                () -> repository.updateHeartbeat(task.id(), task.workerId(), Instant.now()),
                heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> deadline = scheduler.schedule(() -> {
            if (repository.completeTimeout(
                    task.id(), task.workerId(), "任务执行超时", Instant.now())) {
                thread.interrupt();
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        String producedResult = null;
        boolean committed = false;
        try {
            producedResult = dispatcher.execute(task);
            committed = repository.completeSuccess(
                    task.id(), task.workerId(), producedResult, Instant.now());
        } catch (Exception exception) {
            handleFailure(task, exception);
        } finally {
            heartbeat.cancel(false);
            deadline.cancel(false);
            runningThreads.remove(task.id(), thread);
            TaskRecord latest = repository.findById(task.id()).orElse(task);
            if (!committed && producedResult != null
                    && !(latest.status() == TaskStatus.SUCCESS
                    && producedResult.equals(latest.resultData()))) {
                dispatcher.cleanupUncommittedResult(task, producedResult);
            }
            if (latest.status().terminal()) {
                dispatcher.cleanup(latest);
            }
            Thread.interrupted();
        }
    }

    private void handleFailure(TaskRecord claimed, Exception exception) {
        TaskRecord latest = repository.findById(claimed.id()).orElse(claimed);
        if (latest.status() != TaskStatus.RUNNING
                || !claimed.workerId().equals(latest.workerId())) {
            return;
        }
        if (exception instanceof ResourceCapacityException) {
            boolean running = accepting.get();
            if (repository.releaseClaim(latest.id(), latest.workerId(),
                    running ? "RESOURCE_SATURATED" : "WORKER_SHUTDOWN",
                    running ? "任务等待资源配额" : "worker 关闭前释放任务",
                    running ? Instant.now().plus(pollInterval) : Instant.now())) {
                log.debug("任务尚未获得资源，已释放领取: taskId={}", latest.id());
            }
            return;
        }
        String code = accepting.get() ? "TASK_EXECUTION_FAILED" : "WORKER_SHUTDOWN";
        if (latest.attempts() < latest.maxAttempts()) {
            Instant availableAt = accepting.get()
                    ? Instant.now().plus(backoff(latest.attempts()))
                    : Instant.now();
            if (repository.reschedule(latest.id(), latest.workerId(), code,
                    "任务执行失败，等待重试", availableAt)) {
                log.warn("任务执行失败，已重新排队: taskId={}, attempt={}/{}",
                        latest.id(), latest.attempts(), latest.maxAttempts(), exception);
            }
            return;
        }
        if (repository.completeFailure(latest.id(), latest.workerId(), code,
                "任务执行失败", Instant.now())) {
            log.error("任务执行失败且重试次数已耗尽: taskId={}, attempts={}",
                    latest.id(), latest.attempts(), exception);
        }
    }

    private Duration backoff(int attempts) {
        long multiplier = 1L << Math.min(20, Math.max(0, attempts - 1));
        long millis;
        try {
            millis = Math.multiplyExact(retryBaseDelay.toMillis(), multiplier);
        } catch (ArithmeticException exception) {
            millis = retryMaxDelay.toMillis();
        }
        return Duration.ofMillis(Math.min(millis, retryMaxDelay.toMillis()));
    }

    private void recoverStaleSafely() {
        try {
            TaskRepository.RecoveryResult recovered = repository.recoverStale(
                    Instant.now().minus(staleAfter), Instant.now());
            recovered.failed().forEach(dispatcher::cleanup);
            if (recovered.requeued() > 0 || !recovered.failed().isEmpty()) {
                log.warn("恢复失联任务: requeued={}, failed={}",
                        recovered.requeued(), recovered.failed().size());
            }
        } catch (RuntimeException exception) {
            log.error("失联任务恢复失败", exception);
        }
    }

    private void validate(
            int workerCount, Duration poll, Duration taskTimeout,
            Duration heartbeat, Duration stale, Duration recovery,
            Duration retryBase, Duration retryMax, Duration grace,
            int userConcurrency) {
        if (workerCount < 1 || userConcurrency < 1
                || invalid(poll) || invalid(taskTimeout) || invalid(heartbeat)
                || invalid(stale) || invalid(recovery) || invalid(retryBase)
                || invalid(retryMax) || invalid(grace)
                || stale.compareTo(heartbeat) <= 0
                || retryMax.compareTo(retryBase) < 0) {
            throw new IllegalArgumentException("持久化任务 worker 配置无效");
        }
    }

    private boolean invalid(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative();
    }

    private Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
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

    @PreDestroy
    public void shutdown() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
                workers.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            scheduler.shutdownNow();
        }
    }

    public record TaskSubmission(String taskId, boolean duplicate) {
    }

    public static class TaskCapacityException extends RuntimeException {
        public TaskCapacityException(String message) {
            super(message);
        }
    }
}
