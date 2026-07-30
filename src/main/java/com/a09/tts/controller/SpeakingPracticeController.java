package com.a09.tts.controller;

import com.a09.tts.service.SpeakingPracticeService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 🌟 口语练习控制器 🌟
 * - 提供朗读示范
 * - 进行多轮口语对话练习
 * - 生成 AI 评分 & 个性化分析报告
 */

@Slf4j
@RestController
@RequestMapping("/speaking_practice")
public class SpeakingPracticeController {

    @Value("${app.speaking-dir}")
    private String speakingDir;

    private final SpeakingPracticeService speakingPracticeService;
    private Logger log;

    public SpeakingPracticeController(SpeakingPracticeService speakingPracticeService) {
        this.speakingPracticeService = speakingPracticeService;
    }

    /**
     * 获取示范文本和 AI 朗读音频
     * @return 示例文本与语音 URL
     */
    @GetMapping("/example")
    public ResponseEntity<?> getSpeakingExample() {
        try {
            ResponseEntity<?> response = speakingPracticeService.getExampleTextAndAudio();
            return response;
        } catch (Exception e) {
            log.error("❌ 获取示范文本失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("获取示范文本失败：" + e.getMessage());
        }
    }

    /**
     * 口语评测 API（多轮对话支持 + AI 分析报告）
     * @param file      上传语音
     * @param text      被朗读的文本
     * @param mode      评分模式（"standard" | "dialog"）
     * @param sessionId 会话 ID（用于多轮练习）
     * @param language  语言种类（默认 'zh'）
     * @return AI 评分、历史趋势、个性化学习建议
     */
    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluateSpeaking(
            @RequestParam("file") MultipartFile file,
            @RequestParam("text") String text,
            @RequestParam(value = "mode", defaultValue = "standard") String mode,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "language", defaultValue = "zh") String language
    ) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("⚠ 音频文件为空！");
            }

            log.info("🎙 评测请求 - 语言: {} | 模式: {} | 会话: {}", language, mode, sessionId);
            log.info("📖 朗读文本: {}", text);

            // 1. 存储音频文件
            Path audioFilePath = saveFile(file);

            // 2. 调用评测 API
            ResponseEntity<?> response = speakingPracticeService.evaluate(
                    audioFilePath.toString(), text, mode, sessionId, language
            );

            // 3. 删除临时音频文件
            audioFilePath.toFile().deleteOnExit();

            return response;
        } catch (Exception e) {
            log.error("❌ 口语评测失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("口语评测失败：" + e.getMessage());
        }
    }

    /**
     * 存储上传的音频文件
     */
    private Path saveFile(MultipartFile file) throws Exception {
        Path dirPath = Paths.get(speakingDir);
        Files.createDirectories(dirPath);

        String audioFileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path audioFilePath = dirPath.resolve(audioFileName);
        Files.copy(file.getInputStream(), audioFilePath, StandardCopyOption.REPLACE_EXISTING);
        log.info("📁 存储音频文件: {}", audioFilePath);
        return audioFilePath;
    }
}