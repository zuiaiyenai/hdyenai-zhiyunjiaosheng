package com.a09.tts.task;

import java.time.Instant;
import java.util.Optional;

public interface TaskRepository {
    void save(TaskRecord task);

    Optional<TaskRecord> findById(String id);

    Optional<TaskRecord> findByIdAndOwner(String id, String owner);

    int markInterruptedTasksFailed(Instant finishedAt, String reason);
}
