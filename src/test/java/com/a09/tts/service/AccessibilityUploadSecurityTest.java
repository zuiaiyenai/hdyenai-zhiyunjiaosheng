package com.a09.tts.service;

import com.a09.tts.TestMediaFiles;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.service.impl.AccessibilityServiceImpl;
import com.a09.tts.storage.InMemoryStoredObjectMetadataRepository;
import com.a09.tts.storage.LocalObjectStorageService;
import com.a09.tts.storage.ManagedObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class AccessibilityUploadSecurityTest {
    @TempDir
    Path root;

    @Test
    void voiceNotesUseServerKeysAndAreScopedToOwner() throws Exception {
        AccessibilityServiceImpl service = new AccessibilityServiceImpl(
                mock(MoonshotChatClient.class), new UploadSecurityService(),
                new ManagedObjectStorageService(
                        new LocalObjectStorageService(root.toString()),
                        new InMemoryStoredObjectMetadataRepository()));

        Map<String, Object> alice = service.saveVoiceNote(audio(), "Alice note", "alice");
        service.saveVoiceNote(audio(), "Bob note", "bob");

        assertFalse(Path.of(alice.get("audioFilePath").toString()).isAbsolute());
        assertFalse(Path.of(alice.get("noteFilePath").toString()).isAbsolute());
        assertEquals(1, service.listVoiceNotes("alice").get("total"));
        assertEquals(1, service.listVoiceNotes("bob").get("total"));
    }

    private MockMultipartFile audio() {
        return new MockMultipartFile("audio", "note.wav", "audio/wav", TestMediaFiles.wav());
    }
}
