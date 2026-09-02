package com.a09.tts.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.a09.tts.api.VideoSubtitlePreview;
import com.a09.tts.service.VideoVoiceSwapService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@RestController
@RequestMapping("/video_voice_swap")
public class VideoVoiceSwapController {

    private static final Logger log = LoggerFactory.getLogger(VideoVoiceSwapController.class);

    private final VideoVoiceSwapService videoVoiceSwapService;

    public VideoVoiceSwapController(VideoVoiceSwapService videoVoiceSwapService) {
        this.videoVoiceSwapService = videoVoiceSwapService;
    }

    @PostMapping("/process")
    public ResponseEntity<?> processVideo(
            @RequestParam("video") MultipartFile videoFile,
            @RequestParam("voiceType") String voiceType,
            @RequestParam(value = "transcript", required = false) String transcript,
            @RequestParam(value = "subtitles", required = false) String subtitles,
            @RequestParam(value = "includeSubtitles", defaultValue = "true") boolean includeSubtitles) {

        Path videoFilePath = null;
        try {
            videoFilePath = saveFile(videoFile);
            return videoVoiceSwapService.processVideo(videoFilePath.toString(), voiceType,
                    1.0, 1.0, 1.0, transcript, subtitles, includeSubtitles);
        } catch (Exception e) {
            log.error("处理失败", e);
            return ResponseEntity.status(500).body("错误: " + e.getMessage());
        } finally {
            deleteUpload(videoFilePath);
        }
    }

    @PostMapping("/subtitles")
    public ResponseEntity<?> generateSubtitles(@RequestParam("video") MultipartFile videoFile) {
        Path videoFilePath = null;
        try {
            videoFilePath = saveFile(videoFile);
            VideoSubtitlePreview preview = videoVoiceSwapService.generateSubtitlePreview(videoFilePath.toString());
            return ResponseEntity.ok(preview);
        } catch (Exception e) {
            log.error("字幕生成失败", e);
            return ResponseEntity.status(500).body("错误: " + e.getMessage());
        } finally {
            deleteUpload(videoFilePath);
        }
    }

    private Path saveFile(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择有效的视频文件");
        }
        Path tempDir = Paths.get("uploads/video");
        Files.createDirectories(tempDir);
        String originalName = file.getOriginalFilename();
        String safeName = originalName == null ? "video.mp4" : Paths.get(originalName).getFileName().toString();
        safeName = safeName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path filePath = tempDir.resolve(java.util.UUID.randomUUID() + "_" + safeName);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
        return filePath;
    }

    private void deleteUpload(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("临时上传文件清理失败: {}", path, e);
        }
    }
}
