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
                    .findFirst()
                    .orElse(null);
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
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean markRunning(String id, Instant startedAt) {
        return transition(id, null, status -> status == TaskStatus.PENDING, task ->
                update(task, TaskStatus.RUNNING, 10, null, null, startedAt, null));
    }

    @Override
    public synchronized boolean markSucceeded(String id, String resultData, Instant finishedAt) {
        return transition(id, null, status -> status == TaskStatus.RUNNING, task ->
                update(task, TaskStatus.SUCCESS, 100, resultData, null,
                        task.startedAt(), finishedAt));
    }

    @Override
    public synchronized boolean markFailed(String id, String errorMessage, Instant finishedAt) {
        return transition(id, null, status -> !status.terminal(), task ->
                update(task, TaskStatus.FAILED, task.progress(), null, errorMessage,
                        task.startedAt(), finishedAt));
    }

    @Override
    public synchronized boolean markTimedOut(String id, String errorMessage, Instant finishedAt) {
        return transition(id, null, status -> !status.terminal(), task ->
                update(task, TaskStatus.TIMEOUT, task.progress(), null, errorMessage,
                        task.startedAt(), finishedAt));
    }

    @Override
    public synchronized boolean markCancelled(
            String id, String owner, String errorMessage, Instant finishedAt) {
        return transition(id, owner, status -> !status.terminal(), task ->
                update(task, TaskStatus.CANCELLED, task.progress(), task.resultData(), errorMessage,
                        task.startedAt(), finishedAt));
    }

    private boolean transition(String id, String owner, Predicate<TaskStatus> expected,
                               UnaryOperator<TaskRecord> update) {
        TaskRecord current = tasks.get(id);
        if (current == null || owner != null && !owner.equals(current.owner())
                || !expected.test(current.status())) {
            return false;
        }
        tasks.put(id, update.apply(current));
        return true;
    }

    private TaskRecord update(TaskRecord task, TaskStatus status, int progress,
                              String resultData, String errorMessage,
                              Instant startedAt, Instant finishedAt) {
        return new TaskRecord(task.id(), task.owner(), task.type(), status, progress,
                resultData, errorMessage, task.deduplicationKey(), task.createdAt(),
                startedAt, finishedAt);
    }
}
