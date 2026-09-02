package com.a09.tts.cleanup;

import java.util.List;

public interface PendingFileCleanupRepository {
    void enqueue(String storageType, String relativePath);

    List<PendingFileCleanup> findBatch(int limit);

    void markFailed(long id, String errorMessage);

    void delete(long id);
}
