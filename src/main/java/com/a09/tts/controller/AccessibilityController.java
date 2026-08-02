package com.a09.tts.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.a09.tts.service.AccessibilityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/accessibility")
public class AccessibilityController {

    private static final Logger log = LoggerFactory.getLogger(AccessibilityController.class);

    private final AccessibilityService accessibilityService;

    public AccessibilityController(AccessibilityService accessibilityService) {
        this.accessibilityService = accessibilityService;
    }

    /**
     * 文件朗读 - 上传文本文件，读取内容用于TTS合成
     */
    @PostMapping("/read-file")
    public ResponseEntity<Map<String, Object>> readFile(@RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = accessibilityService.readTextFile(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("文件朗读失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "msg", "文件朗读失败: " + e.getMessage()));
        }
    }

    /**
     * 语音笔记 - 上传语音转为文字笔记
     */
    @PostMapping("/voice-note")
    public ResponseEntity<Map<String, Object>> createVoiceNote(
            @RequestParam("audio") MultipartFile audioFile,
            @RequestParam(value = "title", defaultValue = "未命名笔记") String title) {
        try {
            Map<String, Object> result = accessibilityService.saveVoiceNote(audioFile, title);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("语音笔记保存失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "msg", "语音笔记保存失败: " + e.getMessage()));
        }
    }

    /**
     * 获取语音笔记列表
     */
    @GetMapping("/voice-notes")
    public ResponseEntity<Map<String, Object>> listVoiceNotes() {
        try {
            Map<String, Object> result = accessibilityService.listVoiceNotes();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取语音笔记列表失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "msg", "获取语音笔记列表失败: " + e.getMessage()));
        }
    }

    /**
     * 生成学习纪要
     */
    @PostMapping("/generate-summary")
    public ResponseEntity<Map<String, Object>> generateSummary(@RequestBody Map<String, String> request) {
        try {
            String textContent = request.get("text");
            Map<String, Object> result = accessibilityService.generateStudySummary(textContent);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("生成学习纪要失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "msg", "生成学习纪要失败: " + e.getMessage()));
        }
    }

    /**
     * 朗读PPT文件 - 解析PPT内容转为文本
     */
    @PostMapping("/read-ppt")
    public ResponseEntity<Map<String, Object>> readPPT(@RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = accessibilityService.readPPTFile(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("PPT朗读失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "msg", "PPT朗读失败: " + e.getMessage()));
        }
    }
}
