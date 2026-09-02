package com.a09.tts;

import com.a09.tts.repository.CoursewareProjectRepository.ProjectData;
import com.a09.tts.repository.CoursewareProjectRepository.RevisionData;
import com.a09.tts.repository.JdbcCoursewareProjectRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@EnabledIfEnvironmentVariable(named = "MYSQL_INTEGRATION_URL", matches = "jdbc:mysql:.*")
class CoursewarePersistenceMySqlIntegrationTest {

    @Test
    void migratesAndReloadsOwnerScopedCoursewareMetadata() {
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
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            JdbcCoursewareProjectRepository first = new JdbcCoursewareProjectRepository(jdbc);
            Instant now = Instant.now();
            first.save(new ProjectData(
                    "00000000-0000-0000-0000-000000000004", "alice", "数据库恢复",
                    "SUCCEEDED", "owner/project/source.pptx", "owner/project",
                    "source.pptx", "第一版讲稿", 1, "longxiao", 1.0, 1.0, 1.0,
                    "owner/project/narration.wav", null, null, null, now, now));
            first.saveRevision(new RevisionData(
                    "00000000-0000-0000-0000-000000000004", 0,
                    "自动生成", "第一版讲稿", now));

            JdbcCoursewareProjectRepository restarted = new JdbcCoursewareProjectRepository(jdbc);
            ProjectData restored = restarted.findByIdAndOwner(
                    "00000000-0000-0000-0000-000000000004", "alice").orElseThrow();

            assertEquals("owner/project/source.pptx", restored.sourcePath());
            assertEquals("owner/project/narration.wav", restored.audioPath());
            assertEquals(1, restarted.findRevisions(restored.projectId()).size());
            assertFalse(restarted.findByIdAndOwner(restored.projectId(), "bob").isPresent());
            assertEquals(2, jdbc.queryForObject(
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
        if (!schema.matches("tts_phase4_verify_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "MYSQL_INTEGRATION_URL must target a dedicated tts_phase4_verify_* schema");
        }
    }
}
