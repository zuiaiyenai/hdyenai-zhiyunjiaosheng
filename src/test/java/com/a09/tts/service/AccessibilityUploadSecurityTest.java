package com.a09.tts.service;

import com.a09.tts.TestMediaFiles;
import com.a09.tts.api.ResourceNotFoundException;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.service.impl.AccessibilityServiceImpl;
import com.a09.tts.storage.InMemoryStoredObjectMetadataRepository;
import com.a09.tts.storage.LocalObjectStorageService;
import com.a09.tts.storage.ManagedObjectStorageService;
import com.a09.tts.storage.StoredObjectMetadataRepository.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void promotesTaskInputToPersistentVoiceNoteAndRemovesStagingObject() throws Exception {
        ManagedObjectStorageService storage = new ManagedObjectStorageService(
                new LocalObjectStorageService(root.toString()),
                new InMemoryStoredObjectMetadataRepository());
        AccessibilityServiceImpl service = new AccessibilityServiceImpl(
                mock(MoonshotChatClient.class), new UploadSecurityService(), storage);
        String inputKey = "tasks/alice/task/input.wav";
        storage.storeBytes("alice", inputKey, TestMediaFiles.wav(), "audio/wav");

        Map<String, Object> note = service.saveVoiceNoteFromObject(
                inputKey, "note.wav", "Async note", "alice");

        assertThrows(ResourceNotFoundException.class,
                () -> storage.requireMetadata("alice", inputKey));
        storage.requireMetadata("alice", note.get("audioFilePath").toString());
        storage.requireMetadata("alice", note.get("noteFilePath").toString());
    }

    @Test
    void keepsStagingObjectWhenVoiceNotePromotionFails() throws Exception {
        ManagedObjectStorageService storage = mock(ManagedObjectStorageService.class);
        String inputKey = "tasks/alice/task/input.wav";
        when(storage.requireMetadata("alice", inputKey)).thenReturn(new Metadata(
                inputKey, "local", "local", "audio/wav", TestMediaFiles.wav().length,
                "checksum", "alice", Instant.now()));
        doAnswer(invocation -> {
            Path copy = root.resolve("promotion-input.wav");
            Files.write(copy, TestMediaFiles.wav());
            ManagedObjectStorageService.TemporaryFileAction<?> action = invocation.getArgument(2);
            return action.execute(copy);
        }).when(storage).withTemporaryCopy(eq("alice"), eq(inputKey), any());
        doThrow(new IOException("destination unavailable"))
                .when(storage).storeFile(eq("alice"), startsWith("voice_notes/"),
                        any(Path.class), eq("audio/wav"));
        AccessibilityServiceImpl service = new AccessibilityServiceImpl(
                mock(MoonshotChatClient.class), new UploadSecurityService(), storage);

        assertThrows(IOException.class, () -> service.saveVoiceNoteFromObject(
                inputKey, "note.wav", "Async note", "alice"));

        verify(storage, never()).delete("alice", inputKey);
    }

    private MockMultipartFile audio() {
        return new MockMultipartFile("audio", "note.wav", "audio/wav", TestMediaFiles.wav());
    }
}
