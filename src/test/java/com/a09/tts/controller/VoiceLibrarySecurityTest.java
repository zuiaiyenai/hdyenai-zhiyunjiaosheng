package com.a09.tts.controller;

import com.a09.tts.mapper.VoiceMapper;
import com.a09.tts.cleanup.PendingFileCleanupService;
import com.a09.tts.pojo.Voice;
import com.a09.tts.service.VoiceService;
import com.a09.tts.service.impl.VoiceServiceImpl;
import com.a09.tts.util.UploadUtils;
import com.a09.tts.TestMediaFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceLibrarySecurityTest {

    @TempDir
    Path uploadRoot;

    @Test
    void noDbUploadPreviewAndDeleteUseServerGeneratedStorageKey() throws Exception {
        VoiceNoDbController controller = new VoiceNoDbController(uploadRoot.toString());
        MockHttpServletRequest owner = requestFor("alice");
        byte[] content = TestMediaFiles.wav();

        ResponseEntity<Voice> uploaded = controller.upload(
                "测试声音", "测试", false,
                new MockMultipartFile("file", "voice.wav", "audio/wav", content), owner);
        Voice voice = uploaded.getBody();

        assertEquals(HttpStatus.CREATED, uploaded.getStatusCode());
        assertTrue(voice != null);
        assertFalse(Path.of(voice.getFilePath()).isAbsolute());
        assertFalse(voice.getFilePath().contains(".."));
        assertArrayEquals(content, controller.preview(voice.getVoiceId(), owner).getBody());

        MockHttpServletRequest otherUser = requestFor("bob");
        assertEquals(HttpStatus.FORBIDDEN,
                controller.preview(voice.getVoiceId(), otherUser).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller.delete(voice.getVoiceId(), otherUser).getStatusCode());
        Path stored = UploadUtils.resolveWithin(uploadRoot, voice.getFilePath());
        assertTrue(Files.exists(stored));

        assertEquals(HttpStatus.OK, controller.delete(voice.getVoiceId(), owner).getStatusCode());
        assertFalse(Files.exists(stored));
    }

    @Test
    void databaseControllerRejectsDeprecatedAddAndTraversalPreview() throws Exception {
        VoiceService service = mock(VoiceService.class);
        VoiceController controller = new VoiceController();
        ReflectionTestUtils.setField(controller, "voiceService", service);
        ReflectionTestUtils.setField(controller, "uploadDir", uploadRoot.toString());
        Voice voice = privateVoice(7, "alice", "../secret.wav");
        when(service.findById(7)).thenReturn(voice);

        assertEquals(HttpStatus.GONE, controller.addVoice().getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                controller.preview(7, requestFor("alice")).getStatusCode());
    }

    @Test
    void databaseControllerRejectsUnauthorizedReadAndDeleteBeforeFileAccess() throws Exception {
        VoiceService service = mock(VoiceService.class);
        VoiceController controller = new VoiceController();
        ReflectionTestUtils.setField(controller, "voiceService", service);
        ReflectionTestUtils.setField(controller, "uploadDir", uploadRoot.toString());
        Voice voice = privateVoice(8, "alice", "../../outside.wav");
        when(service.findById(8)).thenReturn(voice);

        MockHttpServletRequest otherUser = requestFor("bob");
        assertEquals(HttpStatus.FORBIDDEN, controller.preview(8, otherUser).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.deleteVoice(8, otherUser).getStatusCode());
        verify(service, never()).deleteVoiceById(8);
    }

    @Test
    void previewReturnsNotFoundWhenStoredFileDoesNotExist() throws Exception {
        VoiceService service = mock(VoiceService.class);
        VoiceController controller = new VoiceController();
        ReflectionTestUtils.setField(controller, "voiceService", service);
        ReflectionTestUtils.setField(controller, "uploadDir", uploadRoot.toString());
        when(service.findById(9)).thenReturn(privateVoice(9, "alice", "missing.wav"));

        assertEquals(HttpStatus.NOT_FOUND,
                controller.preview(9, requestFor("alice")).getStatusCode());
    }

    @Test
    void databaseDeleteNeverDeletesOutsideUploadRoot() throws Exception {
        Path nestedRoot = Files.createDirectory(uploadRoot.resolve("voices"));
        Path outside = Files.write(uploadRoot.resolve("protected.wav"), new byte[]{5});
        Voice voice = privateVoice(10, "alice", outside.toString());
        VoiceMapper mapper = mock(VoiceMapper.class);
        when(mapper.findVoiceById(10)).thenReturn(voice);
        when(mapper.deleteVoiceById(10)).thenReturn(1);
        VoiceServiceImpl service = new VoiceServiceImpl();
        PendingFileCleanupService cleanup = mock(PendingFileCleanupService.class);
        ReflectionTestUtils.setField(service, "voiceMapper", mapper);
        ReflectionTestUtils.setField(service, "uploadDir", nestedRoot.toString());
        ReflectionTestUtils.setField(service, "pendingFileCleanupService", cleanup);

        assertEquals(1, service.deleteVoiceById(10));
        assertTrue(Files.exists(outside));
        verify(mapper).deleteVoiceById(10);
        verify(cleanup).deleteOrEnqueue(PendingFileCleanupService.VOICE_STORAGE, outside.toString());
    }

    @Test
    void databaseDeleteRunsFileCleanupOnlyAfterDatabaseDeleteSucceeds() {
        Voice voice = privateVoice(11, "alice", "alice/voice.wav");
        VoiceMapper mapper = mock(VoiceMapper.class);
        PendingFileCleanupService cleanup = mock(PendingFileCleanupService.class);
        when(mapper.findVoiceById(11)).thenReturn(voice);
        when(mapper.deleteVoiceById(11)).thenReturn(1);
        VoiceServiceImpl service = new VoiceServiceImpl();
        ReflectionTestUtils.setField(service, "voiceMapper", mapper);
        ReflectionTestUtils.setField(service, "pendingFileCleanupService", cleanup);

        assertEquals(1, service.deleteVoiceById(11));
        var order = inOrder(mapper, cleanup);
        order.verify(mapper).deleteVoiceById(11);
        order.verify(cleanup).deleteOrEnqueue(
                PendingFileCleanupService.VOICE_STORAGE, "alice/voice.wav");

        VoiceMapper failingMapper = mock(VoiceMapper.class);
        when(failingMapper.findVoiceById(11)).thenReturn(voice);
        when(failingMapper.deleteVoiceById(11)).thenThrow(new IllegalStateException("db unavailable"));
        VoiceServiceImpl failingService = new VoiceServiceImpl();
        ReflectionTestUtils.setField(failingService, "voiceMapper", failingMapper);
        ReflectionTestUtils.setField(failingService, "pendingFileCleanupService", cleanup);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> failingService.deleteVoiceById(11));
        verify(cleanup, org.mockito.Mockito.times(1)).deleteOrEnqueue(
                PendingFileCleanupService.VOICE_STORAGE, "alice/voice.wav");
    }

    @Test
    void databaseDeleteDefersFileCleanupUntilTransactionCommit() {
        Voice voice = privateVoice(12, "alice", "alice/transaction.wav");
        VoiceMapper mapper = mock(VoiceMapper.class);
        PendingFileCleanupService cleanup = mock(PendingFileCleanupService.class);
        when(mapper.findVoiceById(12)).thenReturn(voice);
        when(mapper.deleteVoiceById(12)).thenReturn(1);
        VoiceServiceImpl service = new VoiceServiceImpl();
        ReflectionTestUtils.setField(service, "voiceMapper", mapper);
        ReflectionTestUtils.setField(service, "pendingFileCleanupService", cleanup);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(1, service.deleteVoiceById(12));
            verify(cleanup, never()).deleteOrEnqueue(
                    PendingFileCleanupService.VOICE_STORAGE, "alice/transaction.wav");

            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(cleanup).deleteOrEnqueue(
                    PendingFileCleanupService.VOICE_STORAGE, "alice/transaction.wav");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Voice privateVoice(int id, String owner, String filePath) {
        Voice voice = new Voice();
        voice.setVoiceId(id);
        voice.setOwnerUsername(owner);
        voice.setPublicVisible(false);
        voice.setFilePath(filePath);
        voice.setMimeType("audio/wav");
        return voice;
    }

    private MockHttpServletRequest requestFor(String username) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("username", username);
        return request;
    }
}
