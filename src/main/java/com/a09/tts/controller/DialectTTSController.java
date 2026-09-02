package com.a09.tts.controller;

import com.a09.tts.api.ServiceUnavailableException;
import com.a09.tts.service.AliyunSpeechService;
import com.a09.tts.service.DialectVoiceCatalog;
import com.a09.tts.service.DialectTextEnhancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;

@RestController
@RequestMapping("/dialect")
public class DialectTTSController {
    private static final Logger log = LoggerFactory.getLogger(DialectTTSController.class);
    private final AliyunSpeechService aliyunSpeechService;

    public DialectTTSController(AliyunSpeechService aliyunSpeechService) {
        this.aliyunSpeechService = aliyunSpeechService;
    }

    @GetMapping("/voices")
    public ResponseEntity<?> getDialectVoices() {
        return ResponseEntity.ok(Map.of(
                "provider", "aliyun-nls-2.0",
                "dialects", DialectVoiceCatalog.byDialect(),
                "total", DialectVoiceCatalog.voices().size()));
    }

    @PostMapping("/synthesize")
    public ResponseEntity<?> synthesizeDialect(@RequestBody Map<String, Object> request) {
        String text = request.get("text") instanceof String value ? value.trim() : "";
        String voiceId = request.get("voice") instanceof String value ? value.trim() : "cuijie";
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "文本不能为空"));
        }
        if (text.length() > 5000) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "单次合成文本不能超过 5000 字"));
        }

        try {
            DialectVoiceCatalog.Voice voice = DialectVoiceCatalog.require(voiceId);
            byte[] audio = aliyunSpeechService.synthesize(text, voice.id());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("audio/mpeg"));
            headers.setContentDisposition(ContentDisposition.inline()
                    .filename("dialect-" + voice.id() + ".mp3").build());
            headers.setCacheControl(CacheControl.noStore());
            return new ResponseEntity<>(audio, headers, HttpStatus.OK);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("阿里云方言语音合成失败，voice={}", voiceId, exception);
            throw new ServiceUnavailableException("阿里云方言语音合成失败", exception);
        }
    }

    @PostMapping(value = "/stream", produces = "audio/mpeg")
    public ResponseEntity<?> streamDialect(@RequestBody Map<String, Object> request) {
        String text = request.get("text") instanceof String value ? value.trim() : "";
        String voiceId = request.get("voice") instanceof String value ? value.trim() : "cuijie";
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "文本不能为空"));
        }
        if (text.length() > 5000) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "单次合成文本不能超过 5000 字"));
        }

        try {
            DialectVoiceCatalog.Voice voice = DialectVoiceCatalog.require(voiceId);
            String dialectText = DialectTextEnhancer.enhance(text, voice.dialect());
            StreamingResponseBody body = outputStream ->
                    aliyunSpeechService.stream(dialectText, voice.id(), outputStream);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("audio/mpeg"));
            headers.setContentDisposition(ContentDisposition.inline()
                    .filename("dialect-" + voice.id() + ".mp3").build());
            headers.setCacheControl(CacheControl.noStore());
            return ResponseEntity.ok().headers(headers).body(body);
        } catch (IllegalArgumentException exception) {
            throw exception;
        }
    }
}
