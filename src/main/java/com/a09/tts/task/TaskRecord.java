package com.a09.tts.task;

import java.time.Instant;

public record TaskRecord(
        String id,
        String owner,
        String type,
        TaskStatus status,
        int progress,
        String payload,
        String resultData,
        String errorCode,
        String errorMessage,
        String deduplicationKey,
        int attempts,
        int maxAttempts,
        Instant availableAt,
        Instant createdAt,
        Instant startedAt,
        Instant heartbeatAt,
        Instant finishedAt,
        String workerId,
        long version
) {
    public TaskRecord(
            String id, String owner, String type, TaskStatus status, int progress,
            String resultData, String errorMessage, String deduplicationKey,
            Instant createdAt, Instant startedAt, Instant finishedAt) {
        this(id, owner, type, status, progress, "{}", resultData, null,
                errorMessage, deduplicationKey, 0, 1, createdAt, createdAt,
                startedAt, null, finishedAt, null, 0);
    }
}
