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
    void pptUploadUsesMultipartResourceInsteadOfCopyingWholeFile() throws IOException {
        String implementation = source("service/impl/PPTServiceImpl.java");

        assertFalse(implementation.contains("file.getBytes()"));
        assertTrue(implementation.contains("uploadFile(file.getResource()"));
    }

    private String source(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/java/com/a09/tts").resolve(relativePath));
    }
}
