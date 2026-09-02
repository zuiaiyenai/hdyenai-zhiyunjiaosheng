package com.a09.tts;

import com.a09.tts.pojo.User;
import com.a09.tts.pojo.Voice;
import com.a09.tts.service.UserService;
import com.a09.tts.service.VoiceService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "MYSQL_INTEGRATION_URL", matches = "jdbc:mysql:.*")
class FlywayMySqlIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyMysqlSchemaStartsTwiceAndSupportsCoreDatabaseFlows() throws Exception {
        String url = requiredEnvironment("MYSQL_INTEGRATION_URL");
        String username = requiredEnvironment("MYSQL_INTEGRATION_USERNAME");
        String password = System.getenv().getOrDefault("MYSQL_INTEGRATION_PASSWORD", "");
        requireDedicatedVerificationSchema(url);

        Flyway verifier = Flyway.configure()
                .dataSource(url, username, password)
                .cleanDisabled(false)
                .load();
        verifier.clean();

        int voiceId;
        try {
            try (ConfigurableApplicationContext first = start(url, username, password)) {
                JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
                assertEquals(3, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = DATABASE() "
                                + "AND table_name IN ('user', 'voice', 'speaking_history')",
                        Integer.class));
                assertEquals(4, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class));

                UserService userService = first.getBean(UserService.class);
                User user = new User("phase2_user", "Phase2Password123", null);
                assertEquals(1, userService.register(user));
                assertFalse(user.getPermission());
                assertTrue(userService.login("phase2_user", "Phase2Password123"));

                VoiceService voiceService = first.getBean(VoiceService.class);
                Voice voice = voiceService.upload(
                        "Phase 2 voice", "integration", false, "phase2_user",
                        new MockMultipartFile("file", "voice.wav", "audio/wav", TestMediaFiles.wav()));
                assertNotNull(voice.getVoiceId());
                assertFalse(Path.of(voice.getFilePath()).isAbsolute());
                voiceId = voice.getVoiceId();

                assertEquals(1, jdbc.update(
                        "INSERT INTO speaking_history "
                                + "(session_id, username, reference_text, user_text, fluency_score, "
                                + "pronunciation_score, accuracy_score, correctness_rate, mistakes, feedback, mode, language) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        "phase2-session", "phase2_user", "hello", "hello",
                        100, 100, 100, 100, "", "ok", "standard", "en"));
            }

            try (ConfigurableApplicationContext second = start(url, username, password)) {
                UserService userService = second.getBean(UserService.class);
                VoiceService voiceService = second.getBean(VoiceService.class);
                JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);

                assertTrue(userService.login("phase2_user", "Phase2Password123"));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM voice WHERE voice_id = ?", Integer.class, voiceId));
                assertNotNull(voiceService.findById(voiceId));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM speaking_history WHERE session_id = 'phase2-session'",
                        Integer.class));
                assertEquals(4, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class));
            }
        } finally {
            verifier.clean();
        }
    }

    private ConfigurableApplicationContext start(String url, String username, String password) {
        return new SpringApplicationBuilder(TtsApplication.class)
                .run(
                        "--spring.profiles.active=local",
                        "--spring.autoconfigure.exclude=",
                        "--server.port=0",
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + username,
                        "--spring.datasource.password=" + password,
                        "--spring.flyway.baseline-on-migrate=false",
                        "--spring.flyway.validate-on-migrate=true",
                        "--mybatis.configuration.map-underscore-to-camel-case=true",
                        "--app.redis.enabled=false",
                        "--app.upload-dir=" + tempDir.resolve("voices"),
                        "--jwt.secret=phase2-test-secret-that-is-at-least-32-characters",
                        "--security.auth.enabled=false");
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
        if (!schema.matches("tts_phase2_verify_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "MYSQL_INTEGRATION_URL must target a dedicated tts_phase2_verify_* schema");
        }
    }
}
