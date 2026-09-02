package com.a09.tts.security;

import com.a09.tts.TestMediaFiles;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadSecurityServiceTest {
    @TempDir
    Path root;
    private final UploadSecurityService service = new UploadSecurityService();

    @Test
    void rejectsFakeTypesEmptyTraversalIllegalMimeAndMagicMismatch() {
        assertThrows(IllegalArgumentException.class, () -> service.validate(
                file("fake.mp3", "audio/mpeg", "not mp3".getBytes()), UploadSecurityService.Type.AUDIO));
        assertThrows(IllegalArgumentException.class, () -> service.validate(
                file("fake.jpg", "image/jpeg", "not jpg".getBytes()), UploadSecurityService.Type.IMAGE));
        assertThrows(IllegalArgumentException.class, () -> service.validate(
                file("fake.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "PK fake".getBytes()), UploadSecurityService.Type.PRESENTATION));
        assertThrows(IllegalArgumentException.class, () -> service.validate(
                file("empty.wav", "audio/wav", new byte[0]), UploadSecurityService.Type.AUDIO));
        assertThrows(IllegalArgumentException.class, () -> service.validate(
                file("../voice.wav", "audio/wav", TestMediaFiles.wav()), UploadSecurityService.Type.AUDIO));
        assertThrows(IllegalArgumentException.class, () -> service.validate(
                file("%2e%2e%2fvoice.wav", "audio/wav", TestMediaFiles.wav()), UploadSecurityService.Type.AUDIO));
        assertThrows(IllegalArgumentException.class, () -> service.validate(
                file("voice.wav", "text/plain", TestMediaFiles.wav()), UploadSecurityService.Type.AUDIO));
        assertThrows(IllegalArgumentException.class, () -> service.validate(
                file("voice.wav", "audio/wav", "not wave".getBytes()), UploadSecurityService.Type.AUDIO));
    }

    @Test
    void enforcesSizeAndPerUserQuota() throws Exception {
        ReflectionTestUtils.setField(service, "audioMaxSize", DataSize.ofBytes(8));
        assertThrows(IllegalArgumentException.class, () -> service.validate(
                file("voice.wav", "audio/wav", TestMediaFiles.wav()), UploadSecurityService.Type.AUDIO));

        ReflectionTestUtils.setField(service, "audioMaxSize", DataSize.ofMegabytes(1));
        ReflectionTestUtils.setField(service, "userQuota", DataSize.ofBytes(TestMediaFiles.wav().length));
        assertTrue(Files.isRegularFile(service.save(
                file("voice.wav", "audio/wav", TestMediaFiles.wav()), root,
                UploadSecurityService.Type.AUDIO, "alice")));
        assertThrows(IllegalArgumentException.class, () -> service.save(
                file("voice.wav", "audio/wav", TestMediaFiles.wav()), root,
                UploadSecurityService.Type.AUDIO, "alice"));
        assertDoesNotThrow(() -> service.save(
                file("voice.wav", "audio/wav", TestMediaFiles.wav()), root,
                UploadSecurityService.Type.AUDIO, "bob"));
    }

    @Test
    void acceptsDecodableAudioImageAndPresentation() throws Exception {
        assertDoesNotThrow(() -> service.validate(
                file("voice.wav", "audio/wav", TestMediaFiles.wav()), UploadSecurityService.Type.AUDIO));

        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
        assertDoesNotThrow(() -> service.validate(
                file("avatar.png", "image/png", imageBytes.toByteArray()), UploadSecurityService.Type.IMAGE));

        ByteArrayOutputStream pptBytes = new ByteArrayOutputStream();
        try (XMLSlideShow show = new XMLSlideShow()) {
            show.createSlide();
            show.write(pptBytes);
        }
        assertDoesNotThrow(() -> service.validate(file(
                "lesson.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                pptBytes.toByteArray()), UploadSecurityService.Type.PRESENTATION));
    }

    private MockMultipartFile file(String name, String mime, byte[] content) {
        return new MockMultipartFile("file", name, mime, content);
    }
}
