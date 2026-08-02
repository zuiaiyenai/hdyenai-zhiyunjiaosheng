package com.a09.tts.service.impl;

import com.a09.tts.api.ServiceUnavailableException;
import com.a09.tts.service.SoundCloneService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Map;

@Service
public class SoundCloneServiceImpl implements SoundCloneService {
    private final RestTemplate restTemplate;

    @Value("${sound-clone.api.url}")
    private String apiUrl;

    public SoundCloneServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ResponseEntity<byte[]> soundClone(
            String promptText, String promptLang, String text, String textLang, String audioFilePath) {
        Map<String, Object> request = Map.ofEntries(
                Map.entry("text", text), Map.entry("text_lang", textLang),
                Map.entry("ref_audio_path", audioFilePath.replace("\\", "/")),
                Map.entry("aux_ref_audio_paths", new ArrayList<>()),
                Map.entry("prompt_lang", promptLang), Map.entry("prompt_text", promptText),
                Map.entry("top_k", 5), Map.entry("top_p", 1.0), Map.entry("temperature", 1.0),
                Map.entry("text_split_method", "cut5"), Map.entry("batch_size", 1),
                Map.entry("speed_factor", 1.0), Map.entry("media_type", "wav"),
                Map.entry("streaming_mode", false));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<byte[]> response = restTemplate.postForEntity(
                    apiUrl, new HttpEntity<>(request, headers), byte[].class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new ServiceUnavailableException("声音克隆服务返回异常状态");
            }
            HttpHeaders outputHeaders = new HttpHeaders();
            outputHeaders.setContentType(MediaType.valueOf("audio/wav"));
            outputHeaders.setContentDisposition(ContentDisposition.attachment().filename("cloned.wav").build());
            return new ResponseEntity<>(response.getBody(), outputHeaders, HttpStatus.OK);
        } catch (ServiceUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceUnavailableException("声音克隆服务不可用", exception);
        }
    }
}
