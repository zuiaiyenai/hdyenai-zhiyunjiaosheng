package com.a09.tts.task;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("nodb")
public class InMemoryTaskRepository implements TaskRepository {
    private final ConcurrentHashMap<String, TaskRecord> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(TaskRecord task) {
        tasks.put(task.id(), task);
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
    public int markInterruptedTasksFailed(Instant finishedAt, String reason) {
        int[] updated = {0};
        tasks.replaceAll((id, task) -> {
            if (task.status() != TaskStatus.PENDING && task.status() != TaskStatus.RUNNING) {
                return task;
            }
            updated[0]++;
            return new TaskRecord(task.id(), task.owner(), task.type(), TaskStatus.FAILED,
                    task.progress(), task.resultData(), reason, task.deduplicationKey(),
                    task.createdAt(), task.startedAt(), finishedAt);
        });
        return updated[0];
    }
}
