package com.a09.tts.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "app.storage.provider=aliyun-oss")
@ActiveProfiles("nodb")
@EnabledIfEnvironmentVariable(named = "ALIYUN_OSS_LIVE_TEST", matches = "true")
class AliyunOssLiveIntegrationTest {
    @Autowired
    private ObjectStorageService storage;

    @Autowired
    private ManagedObjectStorageService managedStorage;

    @Test
    void uploadsReadsAndDeletesRealObject() throws Exception {
        byte[] content = "fctts-oss-live-verification".getBytes(StandardCharsets.UTF_8);
        String key = "verification/codex-" + UUID.randomUUID() + ".txt";
        String checksum = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));

        try {
            managedStorage.store("live-verification", key,
                    new ByteArrayInputStream(content), content.length,
                    "text/plain", checksum);
            assertTrue(storage.exists(key));
            try (var input = managedStorage.open("live-verification", key)) {
                assertArrayEquals(content, input.readAllBytes());
            }
        } finally {
            if (storage.exists(key)) {
                managedStorage.delete("live-verification", key);
            }
        }
        assertFalse(storage.exists(key));
    }
}
