package com.a09.tts.controller;

import com.a09.tts.api.AsrResult;
import com.a09.tts.service.ASRService;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.security.UploadSecurityService.Type;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

@RestController
@RequestMapping("/asr")
public class ASRController {
    private final ASRService asrService;
    private final UploadSecurityService uploadSecurity;

    @Value("${app.asr-dir:./uploads/asr}")
    private String asrDir;

    public ASRController(ASRService asrService, UploadSecurityService uploadSecurity) {
        this.asrService = asrService;
        this.uploadSecurity = uploadSecurity;
    }

    @PostMapping("/transcribe")
    public ResponseEntity<AsrResult> transcribeAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", defaultValue = "zh") String language,
            HttpServletRequest request) throws Exception {
        Path saved = uploadSecurity.save(file, Path.of(asrDir), Type.AUDIO, username(request));
        try {
            return ResponseEntity.ok(asrService.transcribeDetailed(saved.toString(), language));
        } finally {
            uploadSecurity.delete(Path.of(asrDir), saved);
        }
    }

    private String username(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "anonymous" : value.toString();
    }
}
