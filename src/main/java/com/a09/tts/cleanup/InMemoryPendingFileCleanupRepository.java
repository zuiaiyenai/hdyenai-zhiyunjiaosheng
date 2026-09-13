package com.a09.tts.cleanup;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
                id, storageType, relativePath, 0, null, null, null, now, now));
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
    public synchronized List<PendingFileCleanup> claimBatch(
            int limit, String claimToken, Instant claimedAt, Instant staleBefore) {
        List<PendingFileCleanup> claimed = entries.values().stream()
                .filter(entry -> entry.claimedAt() == null || entry.claimedAt().isBefore(staleBefore))
                .sorted(Comparator.comparingInt(PendingFileCleanup::attempts)
                        .thenComparing(PendingFileCleanup::updatedAt)
                        .thenComparingLong(PendingFileCleanup::id))
                .limit(limit)
                .map(entry -> new PendingFileCleanup(
                        entry.id(), entry.storageType(), entry.relativePath(), entry.attempts(),
                        entry.lastError(), claimToken, claimedAt,
                        entry.createdAt(), entry.updatedAt()))
                .toList();
        claimed.forEach(entry -> entries.put(entry.id(), entry));
        return claimed;
    }

    @Override
    public synchronized boolean markFailed(long id, String claimToken, String errorMessage) {
        PendingFileCleanup entry = entries.get(id);
        if (entry == null || !Objects.equals(claimToken, entry.claimedBy())) {
            return false;
        }
        entries.put(id, new PendingFileCleanup(
                entry.id(), entry.storageType(), entry.relativePath(), entry.attempts() + 1,
                errorMessage, null, null, entry.createdAt(), Instant.now()));
        return true;
    }

    @Override
    public synchronized boolean complete(long id, String claimToken) {
        PendingFileCleanup entry = entries.get(id);
        if (entry == null || !Objects.equals(claimToken, entry.claimedBy())) {
            return false;
        }
        return entries.remove(id, entry);
    }
}
