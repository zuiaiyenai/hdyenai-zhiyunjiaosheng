package com.a09.tts.cleanup;

import java.time.Instant;

public record PendingFileCleanup(
        long id,
        String storageType,
        String relativePath,
        int attempts,
        String lastError,
        String claimedBy,
        Instant claimedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
