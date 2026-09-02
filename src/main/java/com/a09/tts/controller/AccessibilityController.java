package com.a09.tts.controller;


import com.a09.tts.service.AccessibilityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/accessibility")
public class AccessibilityController {

    private final AccessibilityService accessibilityService;

    public AccessibilityController(AccessibilityService accessibilityService) {
        this.accessibilityService = accessibilityService;
    }

    /**
     * 文件朗读 - 上传文本文件，读取内容用于TTS合成
     */
    @PostMapping("/read-file")
    public ResponseEntity<Map<String, Object>> readFile(@RequestParam("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(accessibilityService.readTextFile(file));
    }

    /**
     * 语音笔记 - 上传语音转为文字笔记
     */
    @PostMapping("/voice-note")
    public ResponseEntity<Map<String, Object>> createVoiceNote(
            @RequestParam("audio") MultipartFile audioFile,
            @RequestParam(value = "title", defaultValue = "未命名笔记") String title,
            HttpServletRequest request) throws Exception {
        Object username = request.getAttribute("username");
        return ResponseEntity.ok(accessibilityService.saveVoiceNote(
                audioFile, title, username == null ? "anonymous" : username.toString()));
    }

    /**
     * 获取语音笔记列表
     */
    @GetMapping("/voice-notes")
    public ResponseEntity<Map<String, Object>> listVoiceNotes(HttpServletRequest request) throws Exception {
        Object username = request.getAttribute("username");
        return ResponseEntity.ok(accessibilityService.listVoiceNotes(
                username == null ? "anonymous" : username.toString()));
    }

    /**
     * 生成学习纪要
     */
    @PostMapping("/generate-summary")
    public ResponseEntity<Map<String, Object>> generateSummary(@RequestBody Map<String, String> request) throws Exception {
        return ResponseEntity.ok(accessibilityService.generateStudySummary(request.get("text")));
    }

    /**
     * 朗读PPT文件 - 解析PPT内容转为文本
     */
    @PostMapping("/read-ppt")
    public ResponseEntity<Map<String, Object>> readPPT(@RequestParam("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(accessibilityService.readPPTFile(file));
    }
}
