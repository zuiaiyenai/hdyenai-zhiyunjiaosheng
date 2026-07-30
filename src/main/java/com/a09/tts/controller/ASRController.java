package com.a09.tts.controller;

import com.a09.tts.service.ASRService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Slf4j
@RestController
@RequestMapping("/asr")
public class ASRController {

    private final ASRService asrService;
    private Logger log;

    public ASRController(ASRService asrService) {
        this.asrService = asrService;
    }

    /**
     * 🎙 **上传音频文件，转换为文本**
     *
     * @param file 上传的音频文件（WAV, MP3 等）
     * @param language 语言标识（"zh"/"en"）
     * @return 识别后的文本
     */
    @PostMapping("/transcribe")
    public ResponseEntity<String> transcribeAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam("language") String language) {

        try {
            Path filePath = saveFile(file, "audio");
            String result = asrService.transcribe(filePath.toString(), language);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("⛔ 语音识别失败", e);
            return ResponseEntity.status(500).body("错误: " + e.getMessage());
        }
    }

    /**
     * 📁 **存储上传的音频**
     */
    private Path saveFile(MultipartFile file, String type) throws Exception {
        Path tempDir = Paths.get("uploads/" + type);
        Files.createDirectories(tempDir);
        Path filePath = tempDir.resolve(System.currentTimeMillis() + "_" + file.getOriginalFilename());
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
        return filePath;
    }
}