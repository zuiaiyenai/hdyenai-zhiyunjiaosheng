package com.a09.tts.task;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

@Repository
@Profile("nodb")
public class InMemoryTaskRepository implements TaskRepository {
    private final ConcurrentHashMap<String, TaskRecord> tasks = new ConcurrentHashMap<>();

    @Override
    public synchronized CreateResult create(TaskRecord task, int perUserConcurrency) {
        if (task.deduplicationKey() != null) {
            TaskRecord existing = tasks.values().stream()
                    .filter(candidate -> candidate.owner().equals(task.owner()))
                    .filter(candidate -> candidate.type().equals(task.type()))
                    .filter(candidate -> task.deduplicationKey().equals(candidate.deduplicationKey()))
                    .filter(candidate -> !candidate.status().terminal())
                    .findFirst().orElse(null);
            if (existing != null) {
                return CreateResult.duplicate(existing);
            }
        }
        long active = tasks.values().stream()
                .filter(candidate -> candidate.owner().equals(task.owner()))
                .filter(candidate -> !candidate.status().terminal())
                .count();
        if (active >= perUserConcurrency) {
            return CreateResult.capacityExceeded();
        }
        tasks.put(task.id(), task);
        return CreateResult.created(task);
    }

    @Override
    public Optional<TaskRecord> findById(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public Optional<TaskRecord> findByIdAndOwner(String id, String owner) {
        TaskRecord task = tasks.get(id);
        return task != null && task.owner().equals(owner) ? Optional.of(task) : Optional.empty();
    }

    @Override
    public List<TaskRecord> findByOwner(String owner, int offset, int limit) {
        return tasks.values().stream()
                .filter(task -> task.owner().equals(owner))
                .sorted(Comparator.comparing(TaskRecord::createdAt).reversed()
                        .thenComparing(TaskRecord::id, Comparator.reverseOrder()))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized Optional<TaskRecord> claimNext(String workerId, Instant now) {
        TaskRecord candidate = tasks.values().stream()
                .filter(task -> task.status() == TaskStatus.PENDING)
                .filter(task -> !task.availableAt().isAfter(now))
                .sorted(Comparator.comparing(TaskRecord::availableAt)
                        .thenComparing(TaskRecord::createdAt).thenComparing(TaskRecord::id))
                .findFirst().orElse(null);
        if (candidate == null) {
            return Optional.empty();
        }
        TaskRecord claimed = state(candidate, TaskStatus.RUNNING, 10,
                candidate.resultData(), null, null, candidate.attempts() + 1,
                candidate.availableAt(), now, now, null, workerId);
        tasks.put(candidate.id(), claimed);
        return Optional.of(claimed);
    }

    @Override
    public synchronized boolean releaseClaim(
            String id, String workerId, String errorCode,
            String errorMessage, Instant availableAt) {
        return transition(id, null,
                task -> task.status() == TaskStatus.RUNNING
                        && workerId.equals(task.workerId()) && task.attempts() > 0,
                task -> state(task, TaskStatus.PENDING, 0, null,
                        errorCode, errorMessage,
                        task.attempts() - 1, availableAt, null, null, null, null));
    }

    @Override
    public synchronized boolean updateHeartbeat(String id, String workerId, Instant heartbeatAt) {
        return transition(id, null,
                task -> task.status() == TaskStatus.RUNNING && workerId.equals(task.workerId()),
                task -> state(task, task.status(), task.progress(), task.resultData(),
                        task.errorCode(), task.errorMessage(), task.attempts(), task.availableAt(),
                        task.startedAt(), heartbeatAt, task.finishedAt(), task.workerId()));
    }

    @Override
    public synchronized boolean completeSuccess(
            String id, String workerId, String resultData, Instant finishedAt) {
        return transition(id, null,
                task -> task.status() == TaskStatus.RUNNING && workerId.equals(task.workerId()),
                task -> state(task, TaskStatus.SUCCESS, 100, resultData, null, null,
                        task.attempts(), task.availableAt(), task.startedAt(),
                        task.heartbeatAt(), finishedAt, null));
    }

    @Override
    public synchronized boolean reschedule(
            String id, String workerId, String errorCode, String errorMessage, Instant availableAt) {
        return transition(id, null,
                task -> task.status() == TaskStatus.RUNNING && workerId.equals(task.workerId()),
                task -> state(task, TaskStatus.PENDING, 0, null, errorCode, errorMessage,
                        task.attempts(), availableAt, null, null, null, null));
    }

    @Override
    public synchronized boolean completeFailure(
            String id, String workerId, String errorCode,
            String errorMessage, Instant finishedAt) {
        return transition(id, null,
                task -> task.status() == TaskStatus.RUNNING && workerId.equals(task.workerId()),
                task -> state(task, TaskStatus.FAILED, task.progress(), null,
                        errorCode, errorMessage, task.attempts(), task.availableAt(),
                        task.startedAt(), task.heartbeatAt(), finishedAt, null));
    }

    @Override
    public synchronized boolean completeTimeout(
            String id, String workerId, String errorMessage, Instant finishedAt) {
        return transition(id, null,
                task -> task.status() == TaskStatus.RUNNING && workerId.equals(task.workerId()),
                task -> state(task, TaskStatus.TIMEOUT, task.progress(), null,
                        "TASK_TIMEOUT", errorMessage, task.attempts(), task.availableAt(),
                        task.startedAt(), task.heartbeatAt(), finishedAt, null));
    }

    @Override
    public synchronized RecoveryResult recoverStale(Instant staleBefore, Instant availableAt) {
        int requeued = 0;
        java.util.ArrayList<TaskRecord> failed = new java.util.ArrayList<>();
        for (TaskRecord task : List.copyOf(tasks.values())) {
            if (task.status() != TaskStatus.RUNNING || task.heartbeatAt() == null
                    || !task.heartbeatAt().isBefore(staleBefore)) {
                continue;
            }
            TaskRecord next;
            if (task.attempts() >= task.maxAttempts()) {
                next = state(task, TaskStatus.FAILED, task.progress(), null,
                        "STALE_ATTEMPTS_EXHAUSTED", "任务心跳超时且重试次数已耗尽",
                        task.attempts(), task.availableAt(), task.startedAt(),
                        task.heartbeatAt(), availableAt, null);
            } else {
                next = state(task, TaskStatus.PENDING, 0, null,
                        "STALE_RECOVERED", "任务因 worker 心跳超时重新排队",
                        task.attempts(), availableAt, null, null, null, null);
            }
            tasks.put(task.id(), next);
            if (next.status() == TaskStatus.FAILED) {
                failed.add(next);
            } else {
                requeued++;
            }
        }
        return new RecoveryResult(requeued, List.copyOf(failed));
    }

    @Override
    public synchronized boolean markRunning(String id, Instant startedAt) {
        return transition(id, null, task -> task.status() == TaskStatus.PENDING, task ->
                state(task, TaskStatus.RUNNING, 10, null, null, null,
                        task.attempts() + 1, task.availableAt(), startedAt, startedAt,
                        null, null));
    }

    @Override
    public synchronized boolean markSucceeded(String id, String resultData, Instant finishedAt) {
        return transition(id, null, task -> task.status() == TaskStatus.RUNNING, task ->
                state(task, TaskStatus.SUCCESS, 100, resultData, null, null,
                        task.attempts(), task.availableAt(), task.startedAt(),
                        task.heartbeatAt(), finishedAt, null));
    }

    @Override
    public synchronized boolean markFailed(String id, String errorMessage, Instant finishedAt) {
        return transition(id, null, task -> !task.status().terminal(), task ->
                state(task, TaskStatus.FAILED, task.progress(), null, "TASK_FAILED",
                        errorMessage, task.attempts(), task.availableAt(), task.startedAt(),
                        task.heartbeatAt(), finishedAt, null));
    }

    @Override
    public synchronized boolean markTimedOut(String id, String errorMessage, Instant finishedAt) {
        return transition(id, null, task -> !task.status().terminal(), task ->
                state(task, TaskStatus.TIMEOUT, task.progress(), null, "TASK_TIMEOUT",
                        errorMessage, task.attempts(), task.availableAt(), task.startedAt(),
                        task.heartbeatAt(), finishedAt, null));
    }

    @Override
    public synchronized boolean markCancelled(
            String id, String owner, String errorMessage, Instant finishedAt) {
        return transition(id, owner, task -> !task.status().terminal(), task ->
                state(task, TaskStatus.CANCELLED, task.progress(), task.resultData(),
                        "TASK_CANCELLED", errorMessage, task.attempts(), task.availableAt(),
                        task.startedAt(), task.heartbeatAt(), finishedAt, null));
    }

    private boolean transition(String id, String owner, Predicate<TaskRecord> expected,
                               UnaryOperator<TaskRecord> update) {
        TaskRecord current = tasks.get(id);
        if (current == null || owner != null && !owner.equals(current.owner())
                || !expected.test(current)) {
            return false;
        }
        tasks.put(id, update.apply(current));
        return true;
    }

    private TaskRecord state(
            TaskRecord task, TaskStatus status, int progress, String resultData,
            String errorCode, String errorMessage, int attempts, Instant availableAt,
            Instant startedAt, Instant heartbeatAt, Instant finishedAt, String workerId) {
        return new TaskRecord(task.id(), task.owner(), task.type(), status, progress,
                task.payload(), resultData, errorCode, errorMessage, task.deduplicationKey(),
                attempts, task.maxAttempts(), availableAt, task.createdAt(), startedAt,
                heartbeatAt, finishedAt, workerId, task.version() + 1);
    }
}
