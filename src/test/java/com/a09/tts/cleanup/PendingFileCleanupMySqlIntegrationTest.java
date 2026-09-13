package com.a09.tts.cleanup;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "MYSQL_INTEGRATION_URL", matches = "jdbc:mysql:.*")
class PendingFileCleanupMySqlIntegrationTest {

    @Test
    void migratesAndPersistsCleanupQueue() {
        String url = requiredEnvironment("MYSQL_INTEGRATION_URL");
        String username = requiredEnvironment("MYSQL_INTEGRATION_USERNAME");
        String password = System.getenv().getOrDefault("MYSQL_INTEGRATION_PASSWORD", "");
        requireDedicatedVerificationSchema(url);
        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        try {
            flyway.migrate();
            JdbcTemplate jdbc = new JdbcTemplate(
                    new DriverManagerDataSource(url, username, password));
            JdbcPendingFileCleanupRepository repository =
                    new JdbcPendingFileCleanupRepository(jdbc);

            repository.enqueue(PendingFileCleanupService.VOICE_STORAGE, "alice/voice.wav");
            Instant now = Instant.now();
            PendingFileCleanup entry = repository.claimBatch(
                    100, "worker-a", now, now.minusSeconds(1)).get(0);
            assertEquals("alice/voice.wav", entry.relativePath());
            assertTrue(repository.markFailed(entry.id(), "worker-a", "文件清理失败"));
            PendingFileCleanup retry = repository.claimBatch(
                    100, "worker-b", now.plusSeconds(1), now).get(0);
            assertEquals(1, retry.attempts());
            assertTrue(repository.complete(retry.id(), "worker-b"));
            assertFalse(repository.complete(retry.id(), "worker-b"));
            assertEquals(0, repository.findBatch(100).size());
            assertEquals(12, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class));
        } finally {
            flyway.clean();
        }
    }

    @Test
    void onlyOneWorkerClaimsAndStaleClaimCanRecover() throws Exception {
        String url = requiredEnvironment("MYSQL_INTEGRATION_URL");
        String username = requiredEnvironment("MYSQL_INTEGRATION_USERNAME");
        String password = System.getenv().getOrDefault("MYSQL_INTEGRATION_PASSWORD", "");
        requireDedicatedVerificationSchema(url);
        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            flyway.migrate();
            JdbcTemplate jdbc = new JdbcTemplate(
                    new DriverManagerDataSource(url, username, password));
            JdbcPendingFileCleanupRepository workerA =
                    new JdbcPendingFileCleanupRepository(jdbc);
            JdbcPendingFileCleanupRepository workerB =
                    new JdbcPendingFileCleanupRepository(jdbc);
            workerA.enqueue(PendingFileCleanupService.VOICE_STORAGE, "alice/concurrent.wav");
            Instant now = Instant.now();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<List<PendingFileCleanup>> claimA = workers.submit(() -> {
                ready.countDown();
                start.await();
                return workerA.claimBatch(1, "worker-a", now, now.minusSeconds(1));
            });
            Future<List<PendingFileCleanup>> claimB = workers.submit(() -> {
                ready.countDown();
                start.await();
                return workerB.claimBatch(1, "worker-b", now, now.minusSeconds(1));
            });
            ready.await();
            start.countDown();
            List<PendingFileCleanup> resultA = claimA.get();
            List<PendingFileCleanup> resultB = claimB.get();

            assertEquals(1, resultA.size() + resultB.size());
            PendingFileCleanup claimed = resultA.isEmpty() ? resultB.get(0) : resultA.get(0);
            String owner = resultA.isEmpty() ? "worker-b" : "worker-a";
            String other = resultA.isEmpty() ? "worker-a" : "worker-b";
            assertTrue(workerA.claimBatch(
                    1, other, now.plusSeconds(60), now.minusSeconds(1)).isEmpty());

            PendingFileCleanup recovered = workerB.claimBatch(
                    1, other, now.plusSeconds(901), now.plusSeconds(1)).get(0);
            assertEquals(claimed.id(), recovered.id());
            assertFalse(workerA.complete(claimed.id(), owner));
            assertTrue(workerB.complete(recovered.id(), other));
        } finally {
            workers.shutdownNow();
            flyway.clean();
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private void requireDedicatedVerificationSchema(String url) {
        String withoutQuery = url.replaceFirst("\\?.*$", "");
        String schema = withoutQuery.substring(withoutQuery.lastIndexOf('/') + 1);
        if (!schema.matches("tts_phase16_cleanup_verify_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "MYSQL_INTEGRATION_URL must target a dedicated "
                            + "tts_phase16_cleanup_verify_* schema");
        }
    }
}
