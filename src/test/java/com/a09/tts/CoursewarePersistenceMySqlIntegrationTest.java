package com.a09.tts;

import com.a09.tts.repository.CoursewareProjectRepository.ProjectData;
import com.a09.tts.repository.CoursewareProjectRepository.RevisionData;
import com.a09.tts.repository.JdbcCoursewareProjectRepository;
import com.a09.tts.storage.JdbcStoredObjectMetadataRepository;
import com.a09.tts.storage.StoredObjectMetadataRepository.Metadata;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                .target("6")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        try {
            flyway.migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO courseware_project (
                        project_id, owner_username, project_name, status, source_path,
                        output_path, file_name, script
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, "legacy-local-courseware", "legacy", "旧课件", "SUCCEEDED",
                    "legacy/project/source.pptx", "legacy/project", "source.pptx", "旧讲稿");

            Flyway.configure().dataSource(url, username, password).load().migrate();
            assertEquals("FAILED", jdbc.queryForObject(
                    "SELECT status FROM courseware_project WHERE project_id = 'legacy-local-courseware'",
                    String.class));

            JdbcCoursewareProjectRepository first = new JdbcCoursewareProjectRepository(jdbc);
            first.save(new ProjectData(
                    "00000000-0000-0000-0000-000000000004", "alice", "数据库恢复",
                    "SUCCEEDED", "courseware/owner/project/source.pptx", "courseware/owner/project",
                    "source.pptx", "第一版讲稿", 1, "longxiao", 1.0, 1.0, 1.0,
                    "courseware/owner/project/narration.wav", null, null, null, now, now));
            first.saveRevision(new RevisionData(
                    "00000000-0000-0000-0000-000000000004", 0,
                    "自动生成", "第一版讲稿", now));

            JdbcCoursewareProjectRepository restarted = new JdbcCoursewareProjectRepository(jdbc);
            ProjectData restored = restarted.findByIdAndOwner(
                    "00000000-0000-0000-0000-000000000004", "alice").orElseThrow();

            assertEquals("courseware/owner/project/source.pptx", restored.sourcePath());
            assertEquals("courseware/owner/project/narration.wav", restored.audioPath());
            assertEquals(0, restored.lockVersion());
            assertEquals(1, first.save(restored));
            assertThrows(OptimisticLockingFailureException.class,
                    () -> restarted.save(restored));
            assertEquals(1, restarted.findRevisions(restored.projectId()).size());
            assertFalse(restarted.findByIdAndOwner(restored.projectId(), "bob").isPresent());

            JdbcStoredObjectMetadataRepository objects =
                    new JdbcStoredObjectMetadataRepository(jdbc);
            objects.save(new Metadata(
                    "courseware/owner/project/source.pptx", "aliyun-oss", "test-bucket",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    123, "a".repeat(64), "alice", now));
            assertEquals(123, objects.sumSizeByOwner("alice"));
            assertEquals(1, objects.findByOwnerAndPrefixAndSuffix(
                    "alice", "courseware/owner/project/", ".pptx", 0, 20).size());
            assertFalse(objects.findByKeyAndOwner(
                    "courseware/owner/project/source.pptx", "bob").isPresent());
            objects.delete("courseware/owner/project/source.pptx", "alice");
            assertEquals(0, objects.sumSizeByOwner("alice"));
            assertEquals(11, jdbc.queryForObject(
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
