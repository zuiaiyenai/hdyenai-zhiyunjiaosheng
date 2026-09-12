package com.a09.tts.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.a09.tts.service.SpeakingPracticeService;
import com.a09.tts.task.AsyncTaskService.TaskSubmission;
import com.a09.tts.task.MediaTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

@RestController
@RequestMapping("/speaking_practice")
public class SpeakingPracticeController {

    private static final Logger log = LoggerFactory.getLogger(SpeakingPracticeController.class);

    private final SpeakingPracticeService speakingPracticeService;
    private final MediaTaskService mediaTasks;

    public SpeakingPracticeController(SpeakingPracticeService speakingPracticeService,
                                      MediaTaskService mediaTasks) {
        this.speakingPracticeService = speakingPracticeService;
        this.mediaTasks = mediaTasks;
    }

    @GetMapping("/example")
    public ResponseEntity<?> getSpeakingExample() throws Exception {
        return speakingPracticeService.getExampleTextAndAudio();
    }

    @PostMapping("/evaluate")
    public ResponseEntity<TaskSubmission> evaluateSpeaking(
            @RequestParam("file") MultipartFile file,
            @RequestParam("text") String text,
            @RequestParam(value = "mode", defaultValue = "standard") String mode,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "language", defaultValue = "zh") String language,
            HttpServletRequest request
    ) throws Exception {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("音频文件为空！");
        }
        log.info("评测请求 - 语言: {} | 模式: {} | 会话: {}", language, mode, sessionId);
        TaskSubmission submission = mediaTasks.submitSpeakingEvaluation(
                file, text, mode, sessionId, language, currentUsername(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(submission);
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request) throws Exception {
        return speakingPracticeService.getHistory(
                sessionId, currentUsername(request), page, size);
    }

    @GetMapping("/dialogue/scenarios")
    public ResponseEntity<?> getDialogueScenarios() throws Exception {
        return speakingPracticeService.getDialogueScenarios();
    }

    @PostMapping("/dialogue/start")
    public ResponseEntity<?> startDialogue(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) throws Exception {
        String scenarioId = request.get("scenarioId");
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("场景ID不能为空");
        }
        return speakingPracticeService.startDialogue(scenarioId, currentUsername(httpRequest));
    }

    @PostMapping("/dialogue/continue")
    public ResponseEntity<?> continueDialogue(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) throws Exception {
        String sessionId = (String) request.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
        return speakingPracticeService.continueDialogue(sessionId, currentUsername(httpRequest));
    }

    private String currentUsername(HttpServletRequest request) {
        Object username = request.getAttribute("username");
        return username == null ? "anonymous" : username.toString();
    }

}
