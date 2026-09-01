package com.a09.tts.service.impl;

import com.a09.tts.api.ServiceUnavailableException;
import com.a09.tts.service.SoundCloneService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

    @Override
    public ResponseEntity<StreamingResponseBody> soundClone(
            String promptText, String promptLang, String text, String textLang, String audioFilePath) {
        Map<String, Object> request = Map.ofEntries(
                Map.entry("text", text), Map.entry("text_lang", textLang),
                Map.entry("ref_audio_path", audioFilePath.replace("\\", "/")),
                Map.entry("aux_ref_audio_paths", new ArrayList<>()),
                Map.entry("prompt_lang", promptLang), Map.entry("prompt_text", promptText),
                Map.entry("top_k", 5), Map.entry("top_p", 1.0), Map.entry("temperature", 1.0),
                Map.entry("text_split_method", "cut5"), Map.entry("batch_size", 1),
                Map.entry("speed_factor", 1.0), Map.entry("media_type", "wav"),
                Map.entry("streaming_mode", true));

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        StreamingResponseBody body = outputStream -> {
            try {
                restTemplate.execute(
                        apiUrl,
                        HttpMethod.POST,
                        restTemplate.httpEntityCallback(new HttpEntity<>(request, requestHeaders)),
                        response -> {
                            if (!response.getStatusCode().is2xxSuccessful()) {
                                throw new ServiceUnavailableException(
                                        "声音克隆服务返回异常状态：" + response.getStatusCode());
                            }
                            response.getBody().transferTo(outputStream);
                            outputStream.flush();
                            return null;
                        });
            } catch (ServiceUnavailableException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ServiceUnavailableException("声音克隆服务不可用", exception);
            }
        };

        HttpHeaders outputHeaders = new HttpHeaders();
        outputHeaders.setContentType(MediaType.valueOf("audio/wav"));
        outputHeaders.setContentDisposition(ContentDisposition.inline().filename("cloned.wav").build());
        outputHeaders.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(body, outputHeaders, HttpStatus.OK);
    }
}
