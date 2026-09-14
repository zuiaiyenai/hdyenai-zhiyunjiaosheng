package com.a09.tts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaStreamingArchitectureTest {

    @Test
    void completedVideoNeverCrossesTheWorkerBoundaryAsByteArray() throws IOException {
        String contract = source("service/VideoVoiceSwapService.java");
        String implementation = source("service/impl/VideoVoiceSwapServiceImpl.java");
        String dispatcher = source("task/TaskWorkDispatcher.java");
        String courseware = source("service/CoursewareProjectService.java");

        assertFalse(contract.contains("ResponseEntity<byte[]>"));
        assertFalse(implementation.contains("Files.readAllBytes"));
        assertFalse(implementation.contains("ttsService.tts("));
        assertTrue(implementation.contains("ttsService.stream("));
        assertFalse(dispatcher.contains("byte[] body = requireBody(response, \"视频换声未生成结果\")"));
        assertFalse(dispatcher.contains(
                "storeBytes(task, payload.uploadId(), \"video.mp4\""));
        assertTrue(dispatcher.contains(
                "objectStorage.storeFile(task.owner(), key, output, \"video/mp4\")"));
        assertFalse(courseware.contains("ResponseEntity<byte[]>"));
        assertFalse(courseware.contains("ttsService.tts("));
        assertTrue(courseware.contains("ttsService.stream("));
    }

    @Test
    void pptTextIsExtractedLocallyInsteadOfUploadingTheWholeFile() throws IOException {
        String implementation = source("service/impl/PPTServiceImpl.java");

        assertFalse(implementation.contains("file.getBytes()"));
        assertFalse(implementation.contains("/files"));
        assertTrue(implementation.contains("new XMLSlideShow(input)"));
        assertTrue(implementation.contains("new HSLFSlideShow(input)"));
    }

    @Test
    void dialectPlayerSubscribesBeforeMediaSourceCanOpen() throws IOException {
        String player = staticAsset("dialect-stream-player.js");

        int subscribe = player.indexOf("var sourceOpenPromise = once(mediaSource, \"sourceopen\"");
        int assignSource = player.indexOf("options.audio.src = activeMediaUrl");
        int fetch = player.indexOf("var response = await fetch");

        assertTrue(subscribe >= 0);
        assertTrue(subscribe < assignSource);
        assertTrue(subscribe < fetch);
    }

    @Test
    void dialectPlayerTurnsUnauthorizedResponsesIntoLoginGuidance() throws IOException {
        String player = staticAsset("dialect-stream-player.js");

        assertTrue(player.contains("if (!options.token)"));
        assertTrue(player.contains("response.status === 401"));
        assertTrue(player.contains("登录已过期，请重新登录"));
        assertTrue(player.contains("localStorage.removeItem(\"token\")"));
        assertTrue(player.contains("localStorage.removeItem(\"role\")"));
        assertTrue(player.contains("localStorage.removeItem(\"username\")"));
        assertTrue(player.contains("document.querySelector(\"header .user\")"));
    }

    @Test
    void dialectPlayerDoesNotBlockStreamReadingWhilePlaybackIsBuffering() throws IOException {
        String player = staticAsset("dialect-stream-player.js");

        assertFalse(player.contains("await playPromise"));
    }

    private String source(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/java/com/a09/tts").resolve(relativePath));
    }

    private String staticAsset(String fileName) throws IOException {
        return Files.readString(Path.of("src/main/resources/static/assets").resolve(fileName));
    }
}
