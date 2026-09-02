package com.a09.tts.cleanup;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("nodb")
public class InMemoryPendingFileCleanupRepository implements PendingFileCleanupRepository {
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<Long, PendingFileCleanup> entries = new ConcurrentHashMap<>();

    @Override
    public void enqueue(String storageType, String relativePath) {
        long id = sequence.incrementAndGet();
        Instant now = Instant.now();
        entries.put(id, new PendingFileCleanup(
                id, storageType, relativePath, 0, null, now, now));
    }

    @Override
    public List<PendingFileCleanup> findBatch(int limit) {
        return entries.values().stream()
                .sorted(Comparator.comparingInt(PendingFileCleanup::attempts)
                        .thenComparing(PendingFileCleanup::updatedAt)
                        .thenComparingLong(PendingFileCleanup::id))
                .limit(limit)
                .toList();
    }

    @Override
    public void markFailed(long id, String errorMessage) {
        entries.computeIfPresent(id, (key, entry) -> new PendingFileCleanup(
                entry.id(), entry.storageType(), entry.relativePath(), entry.attempts() + 1,
                errorMessage, entry.createdAt(), Instant.now()));
    }

    @Override
    public void delete(long id) {
        entries.remove(id);
    }
}
