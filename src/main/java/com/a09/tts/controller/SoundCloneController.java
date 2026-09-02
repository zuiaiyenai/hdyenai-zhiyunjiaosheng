package com.a09.tts.controller;

import com.a09.tts.service.SoundCloneService;
import com.a09.tts.service.AliyunSpeechService;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.security.UploadSecurityService.Type;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.util.Map;

@RestController
@RequestMapping("/sound_clone")
public class SoundCloneController {
    private static final Logger log = LoggerFactory.getLogger(SoundCloneController.class);

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Value("${sound-clone.api.url}")
    private String localApiUrl;

    private final SoundCloneService soundCloneService;
    private final AliyunSpeechService aliyunSpeechService;
    private final UploadSecurityService uploadSecurity;

    public SoundCloneController(SoundCloneService soundCloneService,
                                AliyunSpeechService aliyunSpeechService,
                                UploadSecurityService uploadSecurity) {
        this.soundCloneService = soundCloneService;
        this.aliyunSpeechService = aliyunSpeechService;
        this.uploadSecurity = uploadSecurity;
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
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("msg", exception.getMessage()));
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
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("msg", exception.getMessage()));
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<StreamingResponseBody> soundClone(
            @RequestParam("prompt_text") String promptText,
            @RequestParam("prompt_lang") String promptLang,
            @RequestParam("text") String text,
            @RequestParam("text_lang") String textLang,
            @RequestParam("audioFile") MultipartFile audioFile,
            HttpServletRequest request) {
        Path audioFilePath = null;
        try {
            if (audioFile.isEmpty()) {
                return textResponse(HttpStatus.BAD_REQUEST, "音频文件为空！");
            }
            if (text == null || text.isBlank()) {
                return textResponse(HttpStatus.BAD_REQUEST, "合成文本不能为空！");
            }

            audioFilePath = uploadSecurity.save(audioFile, Paths.get(uploadDir), Type.AUDIO,
                    username(request));
            Path savedAudioPath = audioFilePath;
            ResponseEntity<StreamingResponseBody> response = soundCloneService.soundClone(
                    promptText, promptLang, text, textLang, savedAudioPath.toString());
            StreamingResponseBody upstreamBody = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || upstreamBody == null) {
                deleteQuietly(savedAudioPath);
                return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
            }

            StreamingResponseBody cleaningBody = outputStream -> {
                try {
                    upstreamBody.writeTo(outputStream);
                } finally {
                    deleteQuietly(savedAudioPath);
                }
            };
            return new ResponseEntity<>(cleaningBody, response.getHeaders(), response.getStatusCode());
        } catch (IllegalArgumentException exception) {
            deleteQuietly(audioFilePath);
            return textResponse(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (Exception exception) {
            deleteQuietly(audioFilePath);
            log.error("声音克隆请求失败", exception);
            return textResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed");
        }
    }

    private static String username(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "anonymous" : value.toString();
    }

    private static ResponseEntity<StreamingResponseBody> textResponse(HttpStatus status, String message) {
        StreamingResponseBody body = outputStream ->
                outputStream.write(message.getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(body);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            uploadSecurity.delete(Paths.get(uploadDir), path);
        } catch (Exception exception) {
            log.warn("无法删除声音克隆临时文件：{}", path, exception);
        }
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
