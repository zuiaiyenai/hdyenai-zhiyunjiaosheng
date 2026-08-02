package com.a09.tts.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.a09.tts.service.SpeakingPracticeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

@RestController
@RequestMapping("/speaking_practice")
public class SpeakingPracticeController {

    private static final Logger log = LoggerFactory.getLogger(SpeakingPracticeController.class);

    @Value("${app.speaking-dir:uploads/speaking}")
    private String speakingDir;

    private final SpeakingPracticeService speakingPracticeService;

    public SpeakingPracticeController(SpeakingPracticeService speakingPracticeService) {
        this.speakingPracticeService = speakingPracticeService;
    }

    @GetMapping("/example")
    public ResponseEntity<?> getSpeakingExample() {
        try {
            return speakingPracticeService.getExampleTextAndAudio();
        } catch (Exception e) {
            log.error("获取示范文本失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("获取示范文本失败：" + e.getMessage());
        }
    }

    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluateSpeaking(
            @RequestParam("file") MultipartFile file,
            @RequestParam("text") String text,
            @RequestParam(value = "mode", defaultValue = "standard") String mode,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "language", defaultValue = "zh") String language,
            HttpServletRequest request
    ) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("音频文件为空！");
            }
            log.info("评测请求 - 语言: {} | 模式: {} | 会话: {}", language, mode, sessionId);
            Path audioFilePath = saveFile(file);
            ResponseEntity<?> response = speakingPracticeService.evaluate(
                    audioFilePath.toString(), text, mode, sessionId, language, currentUsername(request)
            );
            audioFilePath.toFile().deleteOnExit();
            return response;
        } catch (Exception e) {
            log.error("口语评测失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("口语评测失败：" + e.getMessage());
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            HttpServletRequest request) {
        try {
            return speakingPracticeService.getHistory(sessionId, currentUsername(request));
        } catch (Exception e) {
            log.error("获取历史记录失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("获取历史记录失败：" + e.getMessage());
        }
    }

    @GetMapping("/dialogue/scenarios")
    public ResponseEntity<?> getDialogueScenarios() {
        try {
            return speakingPracticeService.getDialogueScenarios();
        } catch (Exception e) {
            log.error("获取对话场景失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("获取对话场景失败：" + e.getMessage());
        }
    }

    @PostMapping("/dialogue/start")
    public ResponseEntity<?> startDialogue(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {
        try {
            String scenarioId = request.get("scenarioId");
            if (scenarioId == null) {
                return ResponseEntity.badRequest().body("场景ID不能为空");
            }
            return speakingPracticeService.startDialogue(scenarioId, currentUsername(httpRequest));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            log.error("开始对话失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("开始对话失败：" + e.getMessage());
        }
    }

    @PostMapping("/dialogue/continue")
    public ResponseEntity<?> continueDialogue(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {
        try {
            String sessionId = (String) request.get("sessionId");
            if (sessionId == null || sessionId.isBlank()) {
                return ResponseEntity.badRequest().body("sessionId不能为空");
            }
            return speakingPracticeService.continueDialogue(sessionId, currentUsername(httpRequest));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            log.error("继续对话失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("继续对话失败：" + e.getMessage());
        }
    }

    private String currentUsername(HttpServletRequest request) {
        Object username = request.getAttribute("username");
        return username == null ? "anonymous" : username.toString();
    }

    private Path saveFile(MultipartFile file) throws Exception {
        Path dirPath = Paths.get(speakingDir);
        Files.createDirectories(dirPath);
        String audioFileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path audioFilePath = dirPath.resolve(audioFileName);
        Files.copy(file.getInputStream(), audioFilePath, StandardCopyOption.REPLACE_EXISTING);
        log.info("存储音频文件: {}", audioFilePath);
        return audioFilePath;
    }
}
