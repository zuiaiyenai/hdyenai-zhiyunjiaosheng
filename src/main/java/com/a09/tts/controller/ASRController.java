package com.a09.tts.controller;

import com.a09.tts.api.AsrResult;
import com.a09.tts.service.ASRService;
import com.a09.tts.util.UploadUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@RestController
@RequestMapping("/asr")
public class ASRController {
    private final ASRService asrService;

    @Value("${app.asr-dir:./uploads/asr}")
    private String asrDir;

    public ASRController(ASRService asrService) {
        this.asrService = asrService;
    }

    @PostMapping("/transcribe")
    public ResponseEntity<AsrResult> transcribeAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", defaultValue = "zh") String language) throws Exception {
        Path saved = UploadUtils.save(file, Path.of(asrDir),
                Set.of(".wav", ".mp3", ".m4a", ".flac", ".ogg", ".webm"));
        try {
            return ResponseEntity.ok(asrService.transcribeDetailed(saved.toString(), language));
        } finally {
            Files.deleteIfExists(saved);
        }
    }
}
