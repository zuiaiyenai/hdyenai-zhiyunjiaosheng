package com.a09.tts.task;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "MYSQL_INTEGRATION_URL", matches = "jdbc:mysql:.*")
class AsyncTaskMySqlIntegrationTest {

    @Test
    void migratesLegacyTasksAndEnforcesAtomicStateAcrossRepositoryInstances() throws Exception {
        String url = requiredEnvironment("MYSQL_INTEGRATION_URL");
        String username = requiredEnvironment("MYSQL_INTEGRATION_USERNAME");
        String password = System.getenv().getOrDefault("MYSQL_INTEGRATION_PASSWORD", "");
        requireDedicatedVerificationSchema(url);

        Flyway versionFive = Flyway.configure()
                .dataSource(url, username, password)
                .target("5")
                .cleanDisabled(false)
                .load();
        versionFive.clean();
        try {
            versionFive.migrate();
            JdbcTemplate jdbc = jdbc(url, username, password);
            Instant now = Instant.now();
            insertLegacyTask(jdbc, task("legacy-pending", "legacy", "MEDIA", "same", now));
            insertLegacyTask(jdbc, new TaskRecord(
                    "legacy-running", "legacy", "MEDIA", TaskStatus.RUNNING,
                    10, null, null, "same", now, now, null));

            Flyway.configure().dataSource(url, username, password).load().migrate();

            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM async_task WHERE task_id LIKE 'legacy-%' AND status = 'FAILED'",
                    Integer.class));
            assertEquals(7, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class));

            JdbcTaskRepository first = repository(url, username, password);
            JdbcTaskRepository second = repository(url, username, password);
            verifyConcurrentDeduplication(first, second);
            verifyPerUserCapacity(first, second);
            verifyAtomicTransitions(first, second);

            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM async_task_user_slot", Integer.class));
        } finally {
            Flyway.configure()
                    .dataSource(url, username, password)
                    .cleanDisabled(false)
                    .load()
                    .clean();
        }
    }

    private void verifyConcurrentDeduplication(
            JdbcTaskRepository first, JdbcTaskRepository second) throws Exception {
        Instant now = Instant.now();
        List<TaskRepository.CreateResult> results = race(
                () -> first.create(task(UUID.randomUUID().toString(),
                        "dedup-user", "VIDEO", "project:1", now), 2),
                () -> second.create(task(UUID.randomUUID().toString(),
                        "dedup-user", "VIDEO", "project:1", now), 2));

        assertEquals(1, results.stream()
                .filter(result -> result.disposition() == TaskRepository.CreateDisposition.CREATED)
                .count());
        assertEquals(1, results.stream()
                .filter(result -> result.disposition() == TaskRepository.CreateDisposition.DUPLICATE)
                .count());
        assertEquals(1, results.stream().map(result -> result.task().id()).distinct().count());
        assertTrue(first.markFailed(results.get(0).task().id(), "test cleanup", Instant.now()));
    }

    private void verifyPerUserCapacity(
            JdbcTaskRepository first, JdbcTaskRepository second) throws Exception {
        Instant now = Instant.now();
        List<TaskRepository.CreateResult> results = race(
                () -> first.create(task(UUID.randomUUID().toString(),
                        "capacity-user", "VIDEO", null, now), 1),
                () -> second.create(task(UUID.randomUUID().toString(),
                        "capacity-user", "ASR", null, now), 1));

        assertEquals(1, results.stream()
                .filter(result -> result.disposition() == TaskRepository.CreateDisposition.CREATED)
                .count());
        assertEquals(1, results.stream()
                .filter(result -> result.disposition()
                        == TaskRepository.CreateDisposition.CAPACITY_EXCEEDED)
                .count());
        TaskRecord created = results.stream()
                .filter(result -> result.disposition() == TaskRepository.CreateDisposition.CREATED)
                .findFirst().orElseThrow().task();
        assertTrue(second.markFailed(created.id(), "test cleanup", Instant.now()));
    }

    private void verifyAtomicTransitions(
            JdbcTaskRepository first, JdbcTaskRepository second) throws Exception {
        String id = UUID.randomUUID().toString();
        assertEquals(TaskRepository.CreateDisposition.CREATED,
                first.create(task(id, "transition-user", "VIDEO", null, Instant.now()), 1)
                        .disposition());

        List<Boolean> claimed = race(
                () -> first.markRunning(id, Instant.now()),
                () -> second.markRunning(id, Instant.now()));
        assertEquals(1, claimed.stream().filter(Boolean::booleanValue).count());

        List<Boolean> terminal = race(
                () -> first.markSucceeded(id, "done", Instant.now()),
                () -> second.markFailed(id, "failed", Instant.now()));
        assertEquals(1, terminal.stream().filter(Boolean::booleanValue).count());
        assertTrue(first.findById(id).orElseThrow().status().terminal());
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> firstFuture = executor.submit(() -> {
                start.await();
                return first.call();
            });
            Future<T> secondFuture = executor.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(firstFuture.get(10, TimeUnit.SECONDS),
                    secondFuture.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private TaskRecord task(String id, String owner, String type,
                            String deduplicationKey, Instant createdAt) {
        return new TaskRecord(id, owner, type, TaskStatus.PENDING,
                0, null, null, deduplicationKey, createdAt, null, null);
    }

    private void insertLegacyTask(JdbcTemplate jdbc, TaskRecord task) {
        jdbc.update("""
                INSERT INTO async_task (
                    task_id, owner_username, task_type, status, progress, result_data,
                    error_message, deduplication_key, created_at, started_at, finished_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, task.id(), task.owner(), task.type(), task.status().name(), task.progress(),
                task.resultData(), task.errorMessage(), task.deduplicationKey(),
                java.sql.Timestamp.from(task.createdAt()),
                task.startedAt() == null ? null : java.sql.Timestamp.from(task.startedAt()), null);
    }

    private JdbcTaskRepository repository(String url, String username, String password) {
        return new JdbcTaskRepository(jdbc(url, username, password));
    }

    private JdbcTemplate jdbc(String url, String username, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url, username, password));
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
        if (!schema.matches("tts_phase2_atomic_verify_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "MYSQL_INTEGRATION_URL must target a dedicated tts_phase2_atomic_verify_* schema");
        }
    }
}
