package com.a09.tts.cleanup;

import java.time.Instant;
import java.util.List;

public interface PendingFileCleanupRepository {
    void enqueue(String storageType, String relativePath);

    List<PendingFileCleanup> findBatch(int limit);

    List<PendingFileCleanup> claimBatch(
            int limit, String claimToken, Instant claimedAt, Instant staleBefore);

    boolean markFailed(long id, String claimToken, String errorMessage);

    boolean complete(long id, String claimToken);
}
