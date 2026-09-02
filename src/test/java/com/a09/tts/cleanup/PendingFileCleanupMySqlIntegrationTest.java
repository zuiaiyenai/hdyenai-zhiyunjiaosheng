package com.a09.tts.cleanup;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            PendingFileCleanup entry = repository.findBatch(100).get(0);
            assertEquals("alice/voice.wav", entry.relativePath());
            repository.markFailed(entry.id(), "文件清理失败");
            assertEquals(1, repository.findBatch(100).get(0).attempts());
            repository.delete(entry.id());
            assertEquals(0, repository.findBatch(100).size());
            assertEquals(4, jdbc.queryForObject(
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
        if (!schema.matches("tts_phase6_verify_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "MYSQL_INTEGRATION_URL must target a dedicated tts_phase6_verify_* schema");
        }
    }
}
