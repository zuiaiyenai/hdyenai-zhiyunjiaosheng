package com.a09.tts.controller;

import com.a09.tts.api.TtsRequest;
import com.a09.tts.service.TTSService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/voice")
public class TTSController {

    private static final MediaType AUDIO_WAV = MediaType.parseMediaType("audio/wav");
    private final TTSService ttsService;

    public TTSController(TTSService ttsService) {
        this.ttsService = ttsService;
    }

    @PostMapping(value = "/synthesize", produces = "audio/wav")
    public ResponseEntity<byte[]> textToSpeech(@Valid @RequestBody TtsRequest request) {
        return ttsService.tts(request.text(), request.voice(), request.effectiveSpeed(),
                request.effectivePitch(), request.effectiveRhythm());
    }

    @PostMapping(value = "/stream", produces = "audio/wav")
    public ResponseEntity<StreamingResponseBody> stream(@Valid @RequestBody TtsRequest request) {
        StreamingResponseBody body = outputStream -> ttsService.stream(
                request.text(), request.voice(), request.effectiveSpeed(),
                request.effectivePitch(), request.effectiveRhythm(), outputStream);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(AUDIO_WAV);
        headers.setContentDisposition(ContentDisposition.inline().filename("speech.wav").build());
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
