package com.a09.tts.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用kimi大模型的服务类
 *
 */

@Service
public class PPTService {

    @Value("${moonshot.api.key}")
    private String apiKey;

    @Value("${moonshot.api.base-url}")
    private String baseUrl;

    private final RestTemplate restTemplate;

    public PPTService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    public String processPptAndGenerateContent(MultipartFile file) throws IOException {
        String fileId = uploadFile(file);
        String fileContent = getFileContent(fileId);
        return generateCoursewareContent(fileContent);
    }

    private String uploadFile(MultipartFile file) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(apiKey);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        });
        body.add("purpose", "file-extract");

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl + "/files",
                requestEntity,
                Map.class
        );
        return (String) response.getBody().get("id");
    }

    private String getFileContent(String fileId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        HttpEntity<String> request = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/files/" + fileId + "/content",
                HttpMethod.GET,
                request,
                String.class
        );
        return response.getBody();
    }

    private String generateCoursewareContent(String fileContent) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", "你是 Kimi由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。"
        ));
        messages.add(Map.of(
                "role", "system",
                "content", fileContent
        ));
        messages.add(Map.of(
                "role", "user",
                "content", "请根据 PPT 内容生成上课的课件文本内容，要求结构清晰，重点突出。"
        ));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "moonshot-v1-32k");
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.3);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl + "/chat/completions",
                request,
                Map.class
        );

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> firstChoice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
            if (message != null) {
                return (String) message.get("content");
            }
        }
        return null;
    }
}