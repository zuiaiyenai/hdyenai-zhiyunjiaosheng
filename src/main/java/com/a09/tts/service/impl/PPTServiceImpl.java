package com.a09.tts.service.impl;

import com.a09.tts.service.MoonshotChatClient;
import com.a09.tts.service.PPTService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Service
public class PPTServiceImpl implements PPTService {

    private static final Logger log = LoggerFactory.getLogger(PPTServiceImpl.class);

    @Value("${moonshot.api.key:}")
    private String apiKey;

    @Value("${moonshot.api.base-url:https://api.moonshot.cn/v1}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final MoonshotChatClient moonshotChatClient;

    public PPTServiceImpl(RestTemplate restTemplate, MoonshotChatClient moonshotChatClient) {
        this.restTemplate = restTemplate;
        this.moonshotChatClient = moonshotChatClient;
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
        try {
            log.info("上传文件到 Moonshot: {}", file.getOriginalFilename());
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    baseUrl + "/files", requestEntity, Map.class);
            return (String) response.getBody().get("id");
        } catch (Exception e) {
            log.error("Moonshot 文件上传失败: {}", e.getMessage());
            throw new IOException("Moonshot 文件上传失败: " + e.getMessage());
        }
    }

    private String getFileContent(String fileId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        HttpEntity<String> request = new HttpEntity<>(headers);
        try {
            log.info("获取 Moonshot 文件内容: {}", fileId);
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/files/" + fileId + "/content",
                    HttpMethod.GET, request, String.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Moonshot 文件内容获取失败: {}", e.getMessage());
            return "";
        }
    }

    private String generateCoursewareContent(String fileContent) {
        try {
            log.info("调用 Moonshot 生成课件内容...");
            return moonshotChatClient.generate(
                    "你是 Kimi，由 Moonshot AI 提供的人工智能助手。请提供安全、准确、结构清晰的教学内容。",
                    "请根据以下 PPT 内容生成上课的课件文本，要求结构清晰、重点突出：\n\n" + fileContent);
        } catch (Exception e) {
            log.error("Moonshot 课件生成失败: {}", e.getMessage());
            return "课件生成失败，请检查 Moonshot API 配置。错误: " + e.getMessage();
        }
    }
}
