package com.a09.tts.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.a09.tts.api.VideoSubtitlePreview;
import com.a09.tts.service.VideoVoiceSwapService;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.security.UploadSecurityService.Type;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/video_voice_swap")
public class VideoVoiceSwapController {

    private static final Logger log = LoggerFactory.getLogger(VideoVoiceSwapController.class);

    private final VideoVoiceSwapService videoVoiceSwapService;
    private final UploadSecurityService uploadSecurity;

    @Value("${app.video-dir:./uploads/video}")
    private String videoDir;

    public VideoVoiceSwapController(VideoVoiceSwapService videoVoiceSwapService,
                                    UploadSecurityService uploadSecurity) {
        this.videoVoiceSwapService = videoVoiceSwapService;
        this.uploadSecurity = uploadSecurity;
    }

    @PostMapping("/process")
    public ResponseEntity<?> processVideo(
            @RequestParam("video") MultipartFile videoFile,
            @RequestParam("voiceType") String voiceType,
            @RequestParam(value = "transcript", required = false) String transcript,
            @RequestParam(value = "subtitles", required = false) String subtitles,
            @RequestParam(value = "includeSubtitles", defaultValue = "true") boolean includeSubtitles,
            HttpServletRequest request) {

        Path videoFilePath = null;
        try {
            videoFilePath = uploadSecurity.save(videoFile, Paths.get(videoDir), Type.VIDEO, username(request));
            return videoVoiceSwapService.processVideo(videoFilePath.toString(), voiceType,
                    1.0, 1.0, 1.0, transcript, subtitles, includeSubtitles);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("处理失败", e);
            return ResponseEntity.status(500).body("错误: " + e.getMessage());
        } finally {
            deleteUpload(videoFilePath);
        }
    }

    @PostMapping("/subtitles")
    public ResponseEntity<?> generateSubtitles(@RequestParam("video") MultipartFile videoFile,
                                               HttpServletRequest request) {
        Path videoFilePath = null;
        try {
            videoFilePath = uploadSecurity.save(videoFile, Paths.get(videoDir), Type.VIDEO, username(request));
            VideoSubtitlePreview preview = videoVoiceSwapService.generateSubtitlePreview(videoFilePath.toString());
            return ResponseEntity.ok(preview);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("字幕生成失败", e);
            return ResponseEntity.status(500).body("错误: " + e.getMessage());
        } finally {
            deleteUpload(videoFilePath);
        }
    }

    private String username(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "anonymous" : value.toString();
    }

    private void deleteUpload(Path path) {
        if (path == null) {
            return;
        }
        try {
            uploadSecurity.delete(Paths.get(videoDir), path);
        } catch (Exception e) {
            log.warn("临时上传文件清理失败: {}", path, e);
        }
    }
}
