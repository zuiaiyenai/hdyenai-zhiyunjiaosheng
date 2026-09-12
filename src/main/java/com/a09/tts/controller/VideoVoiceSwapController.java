package com.a09.tts.controller;


import com.a09.tts.task.AsyncTaskService.TaskSubmission;
import com.a09.tts.task.MediaTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/video_voice_swap")
public class VideoVoiceSwapController {
    private final MediaTaskService mediaTasks;

    public VideoVoiceSwapController(MediaTaskService mediaTasks) {
        this.mediaTasks = mediaTasks;
    }

    @PostMapping("/process")
    public ResponseEntity<?> processVideo(
            @RequestParam("video") MultipartFile videoFile,
            @RequestParam("voiceType") String voiceType,
            @RequestParam(value = "transcript", required = false) String transcript,
            @RequestParam(value = "subtitles", required = false) String subtitles,
            @RequestParam(value = "includeSubtitles", defaultValue = "true") boolean includeSubtitles,
            HttpServletRequest request) throws Exception {

        TaskSubmission submission = mediaTasks.submitVideoSwap(
                videoFile, voiceType, transcript, subtitles, includeSubtitles,
                username(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(submission);
    }

    @PostMapping("/subtitles")
    public ResponseEntity<?> generateSubtitles(@RequestParam("video") MultipartFile videoFile,
                                               HttpServletRequest request) throws Exception {
        TaskSubmission submission = mediaTasks.submitVideoSubtitles(
                videoFile, username(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(submission);
    }

    private String username(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "anonymous" : value.toString();
    }

}
