package com.a09.tts.service.impl;

import com.a09.tts.api.ServiceUnavailableException;
import com.a09.tts.service.TTSService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class TTSServiceImpl implements TTSService {
    private static final Logger log = LoggerFactory.getLogger(TTSServiceImpl.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${tts.api.url}")
    private String apiUrl;

    @Value("${tts.output-root:./uploads/output}")
    private String outputRoot;

    public TTSServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<byte[]> tts(String text, String voice) {
        return tts(text, voice, 1.0, 1.0, 1.0);
    }

    public ResponseEntity<byte[]> tts(String text, String voice, double speed, double pitch, double rhythm) {
        if (text == null || text.isBlank() || text.length() > 5000) {
            throw new IllegalArgumentException("文本长度必须在 1 到 5000 字之间");
        }
        if (voice == null || voice.isBlank()) {
            throw new IllegalArgumentException("必须选择音色");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("text", text);
        form.add("voice", voice);
        form.add("speed", String.valueOf(speed));
        form.add("speed_factor", String.valueOf(speed));
        form.add("pitch", String.valueOf(pitch));
        form.add("rhythm", String.valueOf(rhythm));
        form.add("temperature", "0.3");
        form.add("top_p", "0.7");
        form.add("top_k", "20");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.valueOf("audio/wav"), MediaType.APPLICATION_JSON));

        try {
            ResponseEntity<byte[]> response = restTemplate.postForEntity(
                    apiUrl, new HttpEntity<>(form, headers), byte[].class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new ServiceUnavailableException("TTS 服务返回异常状态");
            }

            MediaType contentType = response.getHeaders().getContentType();
            byte[] audio;
            String filename = "speech.wav";
            if (contentType != null && ("audio".equalsIgnoreCase(contentType.getType())
                    || MediaType.APPLICATION_OCTET_STREAM.includes(contentType))) {
                audio = response.getBody();
            } else {
                JsonNode json = objectMapper.readTree(response.getBody());
                String returnedPath = json.path("filename").asText("");
                if (returnedPath.isBlank()) {
                    throw new ServiceUnavailableException("TTS 服务未返回音频或 filename");
                }
                Path allowedRoot = Path.of(outputRoot).toAbsolutePath().normalize();
                Path path = Path.of(returnedPath).toAbsolutePath().normalize();
                if (!path.startsWith(allowedRoot) || !Files.isRegularFile(path)) {
                    throw new ServiceUnavailableException("TTS 服务返回了不允许读取的文件路径");
                }
                audio = Files.readAllBytes(path);
                filename = path.getFileName().toString();
            }

            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.valueOf("audio/wav"));
            responseHeaders.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
            return new ResponseEntity<>(audio, responseHeaders, HttpStatus.OK);
        } catch (ServiceUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("TTS service call failed", exception);
            throw new ServiceUnavailableException("TTS 服务不可用", exception);
        }
    }
}
