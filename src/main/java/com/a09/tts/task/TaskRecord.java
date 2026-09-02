package com.a09.tts.task;

import java.time.Instant;

public record TaskRecord(
        String id,
        String owner,
        String type,
        TaskStatus status,
        int progress,
        String resultData,
        String errorMessage,
        String deduplicationKey,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
