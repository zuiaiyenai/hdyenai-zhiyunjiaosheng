package com.a09.tts.task;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@EnabledIfEnvironmentVariable(named = "MYSQL_INTEGRATION_URL", matches = "jdbc:mysql:.*")
class AsyncTaskMySqlIntegrationTest {

    @Test
    void migratesPersistsScopesAndRecoversTasks() {
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
            JdbcTaskRepository first = new JdbcTaskRepository(jdbc);
            Instant now = Instant.now();
            first.save(new TaskRecord(
                    "00000000-0000-0000-0000-000000000005", "alice", "COURSEWARE_VIDEO",
                    TaskStatus.RUNNING, 10, null, null, "project:1",
                    now, now, null));

            JdbcTaskRepository restarted = new JdbcTaskRepository(jdbc);
            TaskRecord restored = restarted.findByIdAndOwner(
                    "00000000-0000-0000-0000-000000000005", "alice").orElseThrow();
            assertEquals(TaskStatus.RUNNING, restored.status());
            assertFalse(restarted.findByIdAndOwner(restored.id(), "bob").isPresent());
            assertEquals(1, restarted.markInterruptedTasksFailed(
                    Instant.now(), "应用重启导致任务中断"));
            assertEquals(TaskStatus.FAILED,
                    restarted.findById(restored.id()).orElseThrow().status());
            assertEquals(3, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class));
        } finally {
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
        if (!schema.matches("tts_phase5_verify_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "MYSQL_INTEGRATION_URL must target a dedicated tts_phase5_verify_* schema");
        }
    }
}
