package com.a09.tts.controller;

import com.a09.tts.api.ServiceUnavailableException;
import com.a09.tts.service.AliyunSpeechService;
import com.a09.tts.task.AsyncTaskService.TaskSubmission;
import com.a09.tts.task.MediaTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.util.Map;

@RestController
@RequestMapping("/sound_clone")
public class SoundCloneController {
    private static final Logger log = LoggerFactory.getLogger(SoundCloneController.class);

    @Value("${sound-clone.api.url}")
    private String localApiUrl;

    private final AliyunSpeechService aliyunSpeechService;
    private final MediaTaskService mediaTasks;

    public SoundCloneController(AliyunSpeechService aliyunSpeechService,
                                MediaTaskService mediaTasks) {
        this.aliyunSpeechService = aliyunSpeechService;
        this.mediaTasks = mediaTasks;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<?> capabilities() {
        return ResponseEntity.ok(aliyunSpeechService.capabilities(localServiceAvailable(), localApiUrl));
    }

    @PostMapping("/aliyun/clone")
    public ResponseEntity<?> cloneWithAliyun(@RequestBody Map<String, Object> request) {
        String audioUrl = stringValue(request.get("audioUrl"));
        String voicePrefix = stringValue(request.get("voicePrefix"));
        if (!audioUrl.matches("https://.+")) {
            return ResponseEntity.badRequest().body(Map.of("msg", "请填写阿里云可访问的 HTTPS 音频地址"));
        }
        if (!voicePrefix.matches("[a-z0-9]{1,10}")) {
            return ResponseEntity.badRequest().body(Map.of("msg", "音色前缀须为 1–10 位小写字母或数字"));
        }
        try {
            String voiceName = aliyunSpeechService.cloneVoice(voicePrefix, audioUrl);
            return ResponseEntity.ok(Map.of("provider", "aliyun-nls-2.0", "voiceName", voiceName));
        } catch (Exception exception) {
            log.error("阿里云声音复刻失败", exception);
            throw new ServiceUnavailableException("阿里云声音复刻失败", exception);
        }
    }

    @PostMapping("/aliyun/synthesize")
    public ResponseEntity<?> synthesizeAliyunClone(@RequestBody Map<String, Object> request) {
        String text = stringValue(request.get("text"));
        String voiceName = stringValue(request.get("voiceName"));
        if (text.isBlank() || voiceName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("msg", "合成文本和阿里云 VoiceName 不能为空"));
        }
        try {
            byte[] audio = aliyunSpeechService.synthesize(text, voiceName);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("audio/mpeg"));
            headers.setContentDisposition(ContentDisposition.inline().filename("aliyun-clone.mp3").build());
            headers.setCacheControl(CacheControl.noStore());
            return new ResponseEntity<>(audio, headers, HttpStatus.OK);
        } catch (Exception exception) {
            log.error("阿里云复刻音色合成失败，voiceName={}", voiceName, exception);
            throw new ServiceUnavailableException("阿里云复刻音色合成失败", exception);
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<TaskSubmission> soundClone(
            @RequestParam("prompt_text") String promptText,
            @RequestParam("prompt_lang") String promptLang,
            @RequestParam("text") String text,
            @RequestParam("text_lang") String textLang,
            @RequestParam("audioFile") MultipartFile audioFile,
            HttpServletRequest request) throws Exception {
        if (audioFile.isEmpty()) {
            throw new IllegalArgumentException("音频文件为空！");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("合成文本不能为空！");
        }
        TaskSubmission submission = mediaTasks.submitSoundClone(
                audioFile, promptText, promptLang, text, textLang, username(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(submission);
    }

    private static String username(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "anonymous" : value.toString();
    }

    private boolean localServiceAvailable() {
        try {
            URI uri = URI.create(localApiUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), 500);
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string.trim() : "";
    }
}
