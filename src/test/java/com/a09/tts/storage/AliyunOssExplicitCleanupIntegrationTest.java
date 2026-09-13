package com.a09.tts.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = "app.storage.provider=aliyun-oss")
@ActiveProfiles("nodb")
@EnabledIfEnvironmentVariable(named = "ALIYUN_OSS_EXPLICIT_CLEANUP_TEST", matches = "true")
class AliyunOssExplicitCleanupIntegrationTest {
    @Autowired
    private ObjectStorageService storage;

    @Test
    void deletesOnlyExplicitlyListedObjectsAndVerifiesAbsence() throws Exception {
        String rawKeys = System.getenv("ALIYUN_OSS_CLEANUP_KEYS");
        if (rawKeys == null || rawKeys.isBlank()) {
            throw new IllegalStateException("ALIYUN_OSS_CLEANUP_KEYS is required");
        }
        List<String> keys = List.of(rawKeys.split(",")).stream()
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
        if (keys.isEmpty()) {
            throw new IllegalStateException("No cleanup keys were provided");
        }

        int existedBefore = 0;
        for (String key : keys) {
            if (storage.exists(key)) {
                existedBefore++;
                storage.delete(key);
            }
        }
        int remaining = 0;
        for (String key : keys) {
            if (storage.exists(key)) {
                remaining++;
            }
        }

        Path evidencePath = Path.of("target", "phase16-oss-explicit-cleanup.json")
                .toAbsolutePath().normalize();
        Files.createDirectories(evidencePath.getParent());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("phase", "16.6");
        evidence.put("executed_at", Instant.now().toString());
        evidence.put("credentials_recorded", false);
        evidence.put("storage_location_recorded", false);
        evidence.put("requested_objects", keys.size());
        evidence.put("objects_existed_before", existedBefore);
        evidence.put("remaining_objects", remaining);
        evidence.put("status", remaining == 0 ? "VERIFIED" : "FAILED");
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(evidencePath.toFile(), evidence);

        assertEquals(0, remaining);
    }
}
