package com.a09.tts;

import com.a09.tts.repository.JdbcCoursewareProjectRepository;
import com.a09.tts.storage.JdbcStoredObjectMetadataRepository;
import com.a09.tts.storage.StoredObjectMetadataRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "MYSQL_INTEGRATION_URL", matches = "jdbc:mysql:.*")
class DatabaseCapacityMySqlIntegrationTest {

    @Test
    void migratesRepresentativeDataAndUsesIndexesForRealQueries() {
        String url = requiredEnvironment("MYSQL_INTEGRATION_URL");
        String username = requiredEnvironment("MYSQL_INTEGRATION_USERNAME");
        String password = System.getenv().getOrDefault("MYSQL_INTEGRATION_PASSWORD", "");
        requireDedicatedVerificationSchema(url);

        Flyway versionEight = Flyway.configure()
                .dataSource(url, username, password)
                .target("8")
                .cleanDisabled(false)
                .load();
        versionEight.clean();
        try {
            versionEight.migrate();
            JdbcTemplate jdbc = new JdbcTemplate(
                    new DriverManagerDataSource(url, username, password));
            seedRepresentativeData(jdbc);
            logPlan(jdbc, "PHASE7_BEFORE_VOICE_PUBLIC", """
                    SELECT voice_id FROM voice WHERE public_visible = 1
                    ORDER BY created_at DESC, voice_id DESC LIMIT 21
                    """);
            logPlan(jdbc, "PHASE7_BEFORE_VOICE_PRIVATE", """
                    SELECT voice_id FROM voice
                    WHERE owner_username = 'user-7' AND public_visible = 0
                    ORDER BY created_at DESC, voice_id DESC LIMIT 21
                    """);
            logPlan(jdbc, "PHASE7_BEFORE_TASK_LIST", """
                    SELECT task_id FROM async_task WHERE owner_username = 'user-7'
                    ORDER BY created_at DESC, task_id DESC LIMIT 21
                    """);
            logPlan(jdbc, "PHASE7_BEFORE_TASK_HEARTBEAT", """
                    SELECT task_id FROM async_task
                    WHERE status = 'RUNNING' AND heartbeat_at < '2025-01-01 00:00:00'
                      AND attempts < max_attempts
                    """);
            logPlan(jdbc, "PHASE7_BEFORE_OBJECT_NOTES", """
                    SELECT object_key FROM stored_object_metadata
                    WHERE owner_username = 'user-7'
                      AND object_key LIKE 'voice_notes/user-7/%'
                      AND object_key LIKE '%/note.txt'
                    ORDER BY created_at DESC, object_key DESC LIMIT 21
                    """);
            logPlan(jdbc, "PHASE7_BEFORE_SPEAKING_OWNER", """
                    SELECT history_id FROM speaking_history WHERE username = 'user-7'
                    ORDER BY created_at DESC, history_id DESC LIMIT 21
                    """);

            Flyway.configure().dataSource(url, username, password).load().migrate();
            jdbc.queryForList(
                    "ANALYZE TABLE voice, courseware_project, async_task, stored_object_metadata");

            assertEquals(9, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class));
            assertEquals(4, jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT table_name, index_name)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND index_name IN (
                        'idx_voice_owner_visibility_created',
                        'idx_voice_visibility_created',
                        'idx_async_task_status_heartbeat',
                        'idx_stored_object_owner_key'
                    )
                    """, Integer.class));

            assertIndexedWithoutFilesort(jdbc, "idx_voice_visibility_created", """
                    SELECT voice_id FROM voice WHERE public_visible = 1
                    ORDER BY created_at DESC, voice_id DESC LIMIT 21
                    """);
            assertIndexedWithoutFilesort(jdbc, "idx_voice_owner_visibility_created", """
                    SELECT voice_id FROM voice
                    WHERE owner_username = 'user-7' AND public_visible = 0
                    ORDER BY created_at DESC, voice_id DESC LIMIT 21
                    """);
            assertIndexedWithoutFilesort(jdbc, "idx_courseware_project_owner_updated", """
                    SELECT project_id FROM courseware_project WHERE owner_username = 'user-7'
                    ORDER BY updated_at DESC, project_id DESC LIMIT 21
                    """);
            assertIndexedWithoutFilesort(jdbc, "idx_async_task_owner_created", """
                    SELECT task_id FROM async_task WHERE owner_username = 'user-7'
                    ORDER BY created_at DESC, task_id DESC LIMIT 21
                    """);
            assertIndexedWithoutFilesort(jdbc,
                    "idx_async_task_status_available_created", """
                    SELECT task_id FROM async_task
                    WHERE status = 'PENDING' AND available_at <= '2030-01-01 00:00:00'
                    ORDER BY available_at, created_at, task_id LIMIT 1
                    """);
            assertUsesIndex(jdbc, "idx_async_task_status_heartbeat", """
                    SELECT task_id FROM async_task
                    WHERE status = 'RUNNING' AND heartbeat_at < '2025-01-01 00:00:00'
                      AND attempts < max_attempts
                    """);
            assertUsesIndex(jdbc, "uq_async_task_active_deduplication", """
                    SELECT task_id FROM async_task
                    WHERE owner_username = 'user-42' AND task_type = 'VIDEO'
                      AND active_deduplication_key = 'dedup-target'
                    """);
            assertUsesIndex(jdbc, "idx_stored_object_owner_key", """
                    SELECT object_key FROM stored_object_metadata
                    WHERE owner_username = 'user-7'
                      AND object_key LIKE 'voice_notes/user-7/%'
                      AND object_key LIKE '%/note.txt'
                    ORDER BY created_at DESC, object_key DESC LIMIT 21
                    """);
            assertIndexedWithoutFilesort(jdbc,
                    "idx_speaking_history_username_created", """
                    SELECT history_id FROM speaking_history WHERE username = 'user-7'
                    ORDER BY created_at DESC, history_id DESC LIMIT 21
                    """);
            assertIndexedWithoutFilesort(jdbc,
                    "idx_speaking_history_session_user_created", """
                    SELECT history_id FROM speaking_history
                    WHERE session_id = 'session-7' AND username = 'user-7'
                    ORDER BY created_at DESC, history_id DESC LIMIT 21
                    """);
            assertRepresentativeQueriesExecute(jdbc);
        } finally {
            Flyway.configure()
                    .dataSource(url, username, password)
                    .cleanDisabled(false)
                    .load()
                    .clean();
        }
    }

    private void seedRepresentativeData(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE capacity_sequence (n INT NOT NULL PRIMARY KEY)");
        List<Object[]> values = new ArrayList<>();
        for (int value = 0; value < 5000; value++) {
            values.add(new Object[]{value});
        }
        jdbc.batchUpdate("INSERT INTO capacity_sequence (n) VALUES (?)", values);

        jdbc.execute("""
                INSERT INTO voice (
                    voice_name, application_scene, file_path, public_visible,
                    owner_username, created_at
                )
                SELECT CONCAT('voice-', n), 'capacity', CONCAT('voice/', n, '.wav'),
                       MOD(n, 5) = 0, CONCAT('user-', MOD(n, 100)),
                       TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n SECOND
                FROM capacity_sequence WHERE n < 3000
                """);
        jdbc.execute("""
                INSERT INTO courseware_project (
                    project_id, owner_username, project_name, status, source_path,
                    output_path, file_name, script, created_at, updated_at
                )
                SELECT CONCAT('00000000-0000-0000-0007-', LPAD(n, 12, '0')),
                       CONCAT('user-', MOD(n, 100)), CONCAT('project-', n), 'SUCCEEDED',
                       CONCAT('courseware/', n, '/source.pptx'), CONCAT('courseware/', n),
                       'source.pptx', 'script',
                       TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n SECOND,
                       TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n SECOND
                FROM capacity_sequence WHERE n < 2000
                """);
        jdbc.execute("""
                INSERT INTO async_task (
                    task_id, owner_username, task_type, status, progress, payload_json,
                    attempts, max_attempts, available_at, deduplication_key,
                    created_at, started_at, heartbeat_at
                )
                SELECT CONCAT('00000000-0000-0000-0008-', LPAD(n, 12, '0')),
                       CONCAT('user-', MOD(n, 100)), 'VIDEO',
                       CASE WHEN n = 42 THEN 'PENDING'
                            WHEN MOD(n, 4) = 0 THEN 'PENDING'
                            WHEN MOD(n, 4) = 1 THEN 'RUNNING' ELSE 'SUCCESS' END,
                       0, '{}', IF(MOD(n, 4) = 1, 1, 0), 3,
                       TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n SECOND,
                       IF(n = 42, 'dedup-target', NULL),
                       TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n SECOND,
                       IF(MOD(n, 4) = 1, TIMESTAMP('2026-01-01 00:00:00'), NULL),
                       IF(MOD(n, 4) = 1,
                          IF(MOD(n, 500) = 1, TIMESTAMP('2024-01-01 00:00:00'),
                                                 TIMESTAMP('2029-01-01 00:00:00')), NULL)
                FROM capacity_sequence
                """);
        jdbc.execute("""
                INSERT INTO stored_object_metadata (
                    object_key, storage_provider, storage_bucket, content_type,
                    object_size, owner_username, created_at
                )
                SELECT CONCAT(
                           IF(MOD(FLOOR(n / 100), 10) = 0, 'voice_notes/', 'courseware/'),
                           'user-', MOD(n, 100), '/', n,
                           IF(MOD(FLOOR(n / 100), 10) = 0, '/note.txt', '/source.pptx')),
                       'local', 'local', 'application/octet-stream', 1024,
                       CONCAT('user-', MOD(n, 100)),
                       TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n SECOND
                FROM capacity_sequence WHERE n < 3000
                """);
        jdbc.execute("""
                INSERT INTO speaking_history (
                    session_id, username, reference_text, user_text,
                    fluency_score, pronunciation_score, accuracy_score,
                    correctness_rate, mistakes, feedback, mode, language, created_at
                )
                SELECT CONCAT('session-', MOD(n, 20)), CONCAT('user-', MOD(n, 100)),
                       'reference', 'spoken', 90, 90, 90, 100, '', 'ok', 'standard', 'zh',
                       TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n SECOND
                FROM capacity_sequence WHERE n < 3000
                """);
        jdbc.execute("""
                INSERT INTO courseware_project_revision (
                    project_id, revision_number, instruction, script, created_at
                ) VALUES
                    ('00000000-0000-0000-0007-000000000007', 1,
                     'first', 'script-1', '2026-01-01 00:00:00'),
                    ('00000000-0000-0000-0007-000000000007', 2,
                     'second', 'script-2', '2026-01-01 00:00:01'),
                    ('00000000-0000-0000-0007-000000000107', 1,
                     'other', 'script-3', '2026-01-01 00:00:02')
                """);
    }

    private void assertRepresentativeQueriesExecute(JdbcTemplate jdbc) {
        List<Map<String, Object>> voices = jdbc.queryForList("""
                (SELECT voice_id, created_at FROM voice
                 WHERE public_visible = 1
                 ORDER BY created_at DESC, voice_id DESC LIMIT 41)
                UNION ALL
                (SELECT voice_id, created_at FROM voice
                 WHERE owner_username = 'user-7' AND public_visible = 0
                 ORDER BY created_at DESC, voice_id DESC LIMIT 41)
                ORDER BY created_at DESC, voice_id DESC LIMIT 21 OFFSET 20
                """);
        assertEquals(21, voices.size());

        JdbcCoursewareProjectRepository courseware =
                new JdbcCoursewareProjectRepository(jdbc);
        assertEquals(3, courseware.findRevisionsByProjectIds(List.of(
                "00000000-0000-0000-0007-000000000007",
                "00000000-0000-0000-0007-000000000107")).size());

        JdbcStoredObjectMetadataRepository metadata =
                new JdbcStoredObjectMetadataRepository(jdbc);
        List<StoredObjectMetadataRepository.Metadata> notes =
                metadata.findByOwnerAndPrefixAndSuffix(
                        "user-7", "voice_notes/user-7/", "/note.txt", 0, 2);
        assertEquals(2, notes.size());
        assertTrue(notes.stream().allMatch(
                note -> note.objectKey().endsWith("/note.txt")));
    }

    private void assertIndexedWithoutFilesort(
            JdbcTemplate jdbc, String expectedIndex, String sql) {
        List<Map<String, Object>> plan = explain(jdbc, sql);
        assertUsesIndex(plan, expectedIndex);
        assertFalse(plan.stream().map(row -> String.valueOf(row.get("Extra")))
                        .anyMatch(extra -> extra.contains("Using filesort")),
                () -> "unexpected filesort: " + summarize(plan));
        System.out.println("PHASE7_PLAN=" + summarize(plan));
    }

    private void assertUsesIndex(JdbcTemplate jdbc, String expectedIndex, String sql) {
        List<Map<String, Object>> plan = explain(jdbc, sql);
        assertUsesIndex(plan, expectedIndex);
        System.out.println("PHASE7_PLAN=" + summarize(plan));
    }

    private void assertUsesIndex(List<Map<String, Object>> plan, String expectedIndex) {
        assertTrue(plan.stream().anyMatch(row -> expectedIndex.equals(row.get("key"))),
                () -> "expected index " + expectedIndex + ": " + summarize(plan));
    }

    private List<Map<String, Object>> explain(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForList("EXPLAIN " + sql);
    }

    private void logPlan(JdbcTemplate jdbc, String label, String sql) {
        System.out.println(label + "=" + summarize(explain(jdbc, sql)));
    }

    private String summarize(List<Map<String, Object>> plan) {
        return plan.stream()
                .map(row -> "table=" + row.get("table") + ",type=" + row.get("type")
                        + ",key=" + row.get("key") + ",rows=" + row.get("rows")
                        + ",extra=" + row.get("Extra"))
                .reduce((left, right) -> left + " | " + right)
                .orElse("empty");
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
        if (!schema.matches("tts_phase7_capacity_verify_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "MYSQL_INTEGRATION_URL must target a dedicated "
                            + "tts_phase7_capacity_verify_* schema");
        }
    }
}
