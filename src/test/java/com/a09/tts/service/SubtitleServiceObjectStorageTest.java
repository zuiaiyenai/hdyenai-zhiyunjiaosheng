package com.a09.tts.service;

import com.a09.tts.service.impl.SubtitleServiceImpl;
import com.a09.tts.storage.InMemoryStoredObjectMetadataRepository;
import com.a09.tts.storage.LocalObjectStorageService;
import com.a09.tts.storage.ManagedObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SubtitleServiceObjectStorageTest {
    @TempDir
    Path root;

    @Test
    void storesSubtitleBesideOwnerScopedAudioObject() throws Exception {
        ManagedObjectStorageService storage = new ManagedObjectStorageService(
                new LocalObjectStorageService(root.toString()),
                new InMemoryStoredObjectMetadataRepository());
        String audioKey = "courseware/alice/project/narration.wav";
        storage.storeBytes("alice", audioKey, new byte[]{1, 2, 3}, "audio/wav");

        SubtitleService service = new SubtitleServiceImpl(storage);
        String subtitleKey = service.generateSubtitles(audioKey, "zh", "alice");

        assertTrue(subtitleKey.endsWith("/subtitles.srt"));
        try (var input = storage.open("alice", subtitleKey)) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("00:00:01,000"));
        }
    }
}
