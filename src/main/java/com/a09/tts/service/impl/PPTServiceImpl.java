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
import java.util.Locale;
import java.util.Map;

@Service
public class PPTServiceImpl implements PPTService {

    private static final Logger log = LoggerFactory.getLogger(PPTServiceImpl.class);
    private static final String RATE_LIMIT_MESSAGE = "当前使用人数较多，AI 服务暂时繁忙，请稍后重试。";

    @Value("${moonshot.api.key:}")
    private String apiKey;

    @Value("${moonshot.api.base-url:https://api.moonshot.cn/v1}")
    private String baseUrl;

    @Value("${moonshot.api.rate-limit-max-attempts:3}")
    private int rateLimitMaxAttempts = 3;

    @Value("${moonshot.api.rate-limit-retry-delay-ms:1000}")
    private long rateLimitRetryDelayMs = 1000;

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
            return generateWithRetry(
                    "你是专业的教学设计助手。请提供安全、准确、结构清晰、可直接朗读的教学讲稿。",
                    "请根据以下 PPT 内容生成上课讲稿，要求按教学逻辑组织、重点突出，并保留必要的过渡语：\n\n" + fileContent,
                    "课件生成");
        } catch (IllegalStateException exception) {
            return exception.getMessage();
        }
    }

    @Override
    public String optimizeCoursewareContent(String currentScript, String instruction) {
        if (currentScript == null || currentScript.isBlank()) {
            throw new IllegalArgumentException("当前讲稿不能为空");
        }
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("请输入讲稿调整要求");
        }
        return generateWithRetry(
                "你是专业的教学讲稿编辑。只输出修改后的完整讲稿，不解释修改过程，不虚构原稿没有的事实。",
                "当前讲稿：\n" + currentScript + "\n\n本轮调整要求：\n" + instruction,
                "讲稿优化");
    }

    private String generateWithRetry(String systemPrompt, String userPrompt, String operation) {
        int maxAttempts = Math.max(1, rateLimitMaxAttempts);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("调用 Moonshot 执行{}...", operation);
                return moonshotChatClient.generate(systemPrompt, userPrompt);
            } catch (Exception e) {
                if (!isRateLimitError(e)) {
                    log.error("Moonshot {}失败: {}", operation, e.getMessage());
                    throw new IllegalStateException(operation + "失败，请检查 Moonshot API 配置。", e);
                }
                if (attempt == maxAttempts) {
                    log.warn("Moonshot {}持续限流，已尝试 {} 次", operation, attempt);
                    throw new IllegalStateException(RATE_LIMIT_MESSAGE, e);
                }
                log.warn("Moonshot {}被限流，第 {} 次重试将在 {} ms 后执行", operation, attempt,
                        rateLimitRetryDelayMs);
                if (!waitBeforeRetry()) {
                    throw new IllegalStateException(RATE_LIMIT_MESSAGE, e);
                }
            }
        }
        throw new IllegalStateException(RATE_LIMIT_MESSAGE);
    }

    private boolean waitBeforeRetry() {
        try {
            Thread.sleep(Math.max(0, rateLimitRetryDelayMs));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Moonshot 课件生成重试被中断");
            return false;
        }
    }

    private boolean isRateLimitError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("429") || normalized.contains("rate limit")
                    || normalized.contains("rate_limit")) {
                return true;
            }
        }
        return false;
    }
}
