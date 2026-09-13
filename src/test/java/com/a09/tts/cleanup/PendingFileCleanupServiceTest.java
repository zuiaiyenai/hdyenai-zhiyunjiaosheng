package com.a09.tts.cleanup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingFileCleanupServiceTest {

    @TempDir
    Path uploadRoot;

    @Test
    void deletesOnlyNormalizedKeysWithinConfiguredRoot() throws Exception {
        InMemoryPendingFileCleanupRepository repository =
                new InMemoryPendingFileCleanupRepository();
        PendingFileCleanupService service =
                new PendingFileCleanupService(repository, uploadRoot.toString());
        Path stored = uploadRoot.resolve("alice").resolve("voice.wav");
        Files.createDirectories(stored.getParent());
        Files.write(stored, new byte[]{1});
        Path outside = Files.write(uploadRoot.getParent().resolve("protected.wav"), new byte[]{2});

        service.deleteOrEnqueue(PendingFileCleanupService.VOICE_STORAGE, "alice/voice.wav");
        service.deleteOrEnqueue(PendingFileCleanupService.VOICE_STORAGE, "../protected.wav");

        assertTrue(Files.notExists(stored));
        assertTrue(Files.exists(outside));
        assertTrue(repository.findBatch(100).isEmpty());
    }

    @Test
    void retriesPersistedCleanupEntries() throws Exception {
        InMemoryPendingFileCleanupRepository repository =
                new InMemoryPendingFileCleanupRepository();
        PendingFileCleanupService service =
                new PendingFileCleanupService(repository, uploadRoot.toString());
        Path stored = uploadRoot.resolve("alice").resolve("retry.wav");
        Files.createDirectories(stored.getParent());
        Files.write(stored, new byte[]{1});
        repository.enqueue(PendingFileCleanupService.VOICE_STORAGE, "alice/retry.wav");

        service.retryPendingFiles();

        assertTrue(Files.notExists(stored));
        assertTrue(repository.findBatch(100).isEmpty());
    }

    @Test
    void dropsUnsafePersistedEntryWithoutTouchingOutsideFile() throws Exception {
        InMemoryPendingFileCleanupRepository repository =
                new InMemoryPendingFileCleanupRepository();
        PendingFileCleanupService service =
                new PendingFileCleanupService(repository, uploadRoot.toString());
        Path outside = Files.write(uploadRoot.getParent().resolve("retry-protected.wav"),
                new byte[]{2});
        repository.enqueue(PendingFileCleanupService.VOICE_STORAGE, "../retry-protected.wav");

        service.retryPendingFiles();

        assertTrue(Files.exists(outside));
        assertTrue(repository.findBatch(100).isEmpty());
    }

    @Test
    void prioritizesFreshEntriesOverRepeatedFailures() {
        InMemoryPendingFileCleanupRepository repository =
                new InMemoryPendingFileCleanupRepository();
        repository.enqueue(PendingFileCleanupService.VOICE_STORAGE, "alice/old.wav");
        Instant now = Instant.now();
        PendingFileCleanup old = repository.claimBatch(
                1, "worker-a", now, now.minusSeconds(1)).get(0);
        repository.markFailed(old.id(), "worker-a", "文件清理失败");
        repository.enqueue(PendingFileCleanupService.VOICE_STORAGE, "alice/new.wav");

        assertTrue(repository.findBatch(1).get(0).relativePath().endsWith("new.wav"));
    }

    @Test
    void recoversStaleClaimRetriesAndCompletesIdempotently() {
        InMemoryPendingFileCleanupRepository repository =
                new InMemoryPendingFileCleanupRepository();
        repository.enqueue(PendingFileCleanupService.VOICE_STORAGE, "alice/stale.wav");
        Instant claimedAt = Instant.parse("2026-09-13T00:00:00Z");

        PendingFileCleanup first = repository.claimBatch(
                1, "worker-a", claimedAt, claimedAt.minusSeconds(1)).get(0);
        assertTrue(repository.claimBatch(
                1, "worker-b", claimedAt.plusSeconds(60), claimedAt.minusSeconds(1)).isEmpty());

        PendingFileCleanup recovered = repository.claimBatch(
                1, "worker-b", claimedAt.plusSeconds(901), claimedAt.plusSeconds(1)).get(0);
        assertEquals(first.id(), recovered.id());
        assertTrue(repository.markFailed(recovered.id(), "worker-b", "retry"));

        PendingFileCleanup retry = repository.claimBatch(
                1, "worker-c", claimedAt.plusSeconds(902), claimedAt.plusSeconds(901)).get(0);
        assertEquals(1, retry.attempts());
        assertTrue(repository.complete(retry.id(), "worker-c"));
        assertFalse(repository.complete(retry.id(), "worker-c"));
    }
}
