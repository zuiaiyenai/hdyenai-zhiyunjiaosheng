package com.a09.tts.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalObjectStorageServiceTest {
    @TempDir
    Path root;

    @Test
    void storesStreamsAndDeletesObjectWithinConfiguredRoot() throws Exception {
        LocalObjectStorageService storage = new LocalObjectStorageService(root.toString());
        byte[] content = new byte[]{1, 2, 3};

        StoredObject stored = storage.store(
                "voice_samples/alice/sample.wav",
                new ByteArrayInputStream(content), content.length, "audio/wav", "checksum");

        assertEquals("voice_samples/alice/sample.wav", stored.objectKey());
        assertEquals("local", stored.provider());
        assertTrue(storage.exists(stored.objectKey()));
        try (var input = storage.open(stored.objectKey())) {
            assertArrayEquals(content, input.readAllBytes());
        }
        storage.delete(stored.objectKey());
        assertFalse(storage.exists(stored.objectKey()));
    }

    @Test
    void rejectsTraversalKeysWithoutTouchingOutsideFile() throws Exception {
        LocalObjectStorageService storage = new LocalObjectStorageService(root.toString());
        Path outside = Files.write(root.getParent().resolve("protected.wav"), new byte[]{9});

        assertThrows(IllegalArgumentException.class,
                () -> storage.delete("../protected.wav"));

        assertTrue(Files.exists(outside));
    }
}
