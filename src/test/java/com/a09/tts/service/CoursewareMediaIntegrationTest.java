package com.a09.tts.service;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoursewareMediaIntegrationTest {

    @TempDir
    Path tempDirectory;

    @Test
    void rendersPptAndCombinesNarrationAndAvatarIntoMp4() throws Exception {
        Assumptions.assumeTrue(commandAvailable("ffmpeg") && commandAvailable("ffprobe"));

        PPTService pptService = mock(PPTService.class);
        TTSService ttsService = mock(TTSService.class);
        when(pptService.processPptAndGenerateContent(any())).thenReturn("欢迎学习人工智能导论。");
        when(ttsService.tts(anyString(), anyString(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(ResponseEntity.ok(oneSecondWav()));

        CoursewareProjectService service = new CoursewareProjectService(pptService, ttsService);
        ReflectionTestUtils.setField(service, "coursewareDir", tempDirectory.toString());
        ReflectionTestUtils.setField(service, "ffmpegPath", "ffmpeg");
        ReflectionTestUtils.setField(service, "ffprobePath", "ffprobe");

        MockMultipartFile ppt = new MockMultipartFile("file", "媒体闭环.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                oneSlidePptx());
        CoursewareProjectService.ProjectView project = service.create(ppt, "alice");
        service.generateAudio(project.id(), "alice", "longxiao", 1.0, 1.0, 1.0);
        service.uploadAvatar(project.id(), "alice", new MockMultipartFile(
                "avatar", "teacher.png", "image/png", avatarPng()));

        CoursewareProjectService.ProjectView video = service.generateVideo(project.id(), "alice");
        assertTrue(video.videoReady());
        CoursewareProjectService.DownloadArtifact artifact =
                service.download(project.id(), "alice", "video");
        assertTrue(Files.size(artifact.path()) > 1000);
    }

    private byte[] oneSlidePptx() throws Exception {
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFTextBox textBox = show.createSlide().createTextBox();
            textBox.setText("人工智能导论\n第一章：基础概念");
            show.write(output);
            return output.toByteArray();
        }
    }

    private byte[] oneSecondWav() throws Exception {
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        byte[] pcm = new byte[16000 * 2];
        try (ByteArrayInputStream input = new ByteArrayInputStream(pcm);
             AudioInputStream audio = new AudioInputStream(input, format, 16000);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output);
            return output.toByteArray();
        }
    }

    private byte[] avatarPng() throws Exception {
        BufferedImage image = new BufferedImage(160, 240, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(65, 135, 180));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.WHITE);
            graphics.fillOval(40, 25, 80, 80);
            graphics.fillRect(55, 105, 50, 110);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private boolean commandAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "-version")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception exception) {
            return false;
        }
    }
}
