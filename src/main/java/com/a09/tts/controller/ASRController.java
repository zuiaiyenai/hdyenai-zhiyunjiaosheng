package com.a09.tts.controller;

import com.a09.tts.task.AsyncTaskService.TaskSubmission;
import com.a09.tts.task.MediaTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/asr")
public class ASRController {
    private final MediaTaskService mediaTasks;

    public ASRController(MediaTaskService mediaTasks) {
        this.mediaTasks = mediaTasks;
    }

    @PostMapping("/transcribe")
    public ResponseEntity<TaskSubmission> transcribeAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", defaultValue = "zh") String language,
            HttpServletRequest request) throws Exception {
        TaskSubmission submission = mediaTasks.submitAsr(
                file, language, username(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(submission);
    }

    private String username(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "anonymous" : value.toString();
    }
}
