package com.a09.tts.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            @RequestParam("voiceType") String voiceType) {

        try {
            Path videoFilePath = saveFile(videoFile);
            return videoVoiceSwapService.processVideo(videoFilePath.toString(), voiceType, 1.0, 1.0, 1.0);
        } catch (Exception e) {
            log.error("处理失败", e);
            return ResponseEntity.status(500).body("错误: " + e.getMessage());
        }
    }

    private Path saveFile(MultipartFile file) throws Exception {
        Path tempDir = Paths.get("uploads/video");
        Files.createDirectories(tempDir);
        Path filePath = tempDir.resolve(System.currentTimeMillis() + "_" + file.getOriginalFilename());
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
        return filePath;
    }
}