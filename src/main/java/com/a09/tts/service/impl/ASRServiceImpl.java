package com.a09.tts.service.impl;

import com.a09.tts.api.AsrResult;
import com.a09.tts.api.ServiceUnavailableException;
import com.a09.tts.service.ASRService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class ASRServiceImpl implements ASRService {
    private static final Logger log = LoggerFactory.getLogger(ASRServiceImpl.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${asr.api.url}")
    private String apiUrl;

    @Value("${asr.api.token:}")
    private String accessToken;

    public ASRServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String transcribe(String filePath, String language) {
        return transcribeDetailed(filePath, language).text();
    }

    public AsrResult transcribeDetailed(String filePath, String language) {
        Path audio = Path.of(filePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(audio)) {
            throw new IllegalArgumentException("音频文件不存在");
        }
        if (language == null || !language.matches("[a-zA-Z_-]{2,12}")) {
            throw new IllegalArgumentException("语言参数格式不正确");
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(audio));
        body.add("language", language);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (!accessToken.isBlank()) {
            headers.setBearerAuth(accessToken);
        }

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl, new HttpEntity<>(body, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new ServiceUnavailableException("ASR 服务返回异常状态");
            }
            return parse(response.getBody());
        } catch (ServiceUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("ASR service call failed", exception);
            throw new ServiceUnavailableException("ASR 服务不可用", exception);
        }
    }

    private AsrResult parse(String body) throws Exception {
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            if (trimmed.isBlank()) {
                throw new ServiceUnavailableException("ASR 服务返回空文本");
            }
            return new AsrResult(trimmed, null, null, null, List.of());
        }
        JsonNode json = objectMapper.readTree(trimmed);
        String text = firstText(json, "text", "transcript", "result");
        if (text == null || text.isBlank()) {
            throw new ServiceUnavailableException("ASR 服务响应缺少 text 字段");
        }
        List<AsrResult.Segment> segments = new ArrayList<>();
        JsonNode segmentNodes = json.path("segments");
        if (segmentNodes.isArray()) {
            for (JsonNode segment : segmentNodes) {
                segments.add(new AsrResult.Segment(
                        segment.path("start").asDouble(),
                        segment.path("end").asDouble(),
                        segment.path("text").asText()));
            }
        }
        return new AsrResult(text, number(json, "fluency"), number(json, "pronunciation"),
                number(json, "accuracy"), segments);
    }

    private String firstText(JsonNode json, String... fields) {
        for (String field : fields) {
            JsonNode value = json.path(field);
            if (value.isTextual()) {
                return value.asText();
            }
            if (value.isArray() && !value.isEmpty()) {
                return value.get(0).asText();
            }
        }
        return null;
    }

    private Double number(JsonNode json, String field) {
        JsonNode value = json.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }
}
