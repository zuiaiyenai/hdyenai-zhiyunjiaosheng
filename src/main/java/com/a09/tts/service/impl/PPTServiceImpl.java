package com.a09.tts.service.impl;

import com.a09.tts.service.MoonshotChatClient;
import com.a09.tts.service.PPTService;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;

@Service
public class PPTServiceImpl implements PPTService {

    private static final Logger log = LoggerFactory.getLogger(PPTServiceImpl.class);
    private static final String RATE_LIMIT_MESSAGE = "当前使用人数较多，AI 服务暂时繁忙，请稍后重试。";

    @Value("${moonshot.api.rate-limit-max-attempts:3}")
    private int rateLimitMaxAttempts = 3;

    @Value("${moonshot.api.rate-limit-retry-delay-ms:1000}")
    private long rateLimitRetryDelayMs = 1000;

    private final MoonshotChatClient moonshotChatClient;

    public PPTServiceImpl(MoonshotChatClient moonshotChatClient) {
        this.moonshotChatClient = moonshotChatClient;
    }

    public String processPptAndGenerateContent(MultipartFile file) throws IOException {
        return generateCoursewareContent(
                extractPptText(file.getResource(), file.getOriginalFilename()));
    }

    @Override
    public String processPptAndGenerateContent(Path file, String originalFilename) throws IOException {
        return generateCoursewareContent(
                extractPptText(new org.springframework.core.io.FileSystemResource(file),
                        originalFilename));
    }

    private String extractPptText(Resource file, String originalFilename) throws IOException {
        String filename = originalFilename == null ? "" : originalFilename.toLowerCase(Locale.ROOT);
        StringBuilder content = new StringBuilder();
        try (InputStream input = file.getInputStream()) {
            if (filename.endsWith(".pptx")) {
                try (XMLSlideShow show = new XMLSlideShow(input)) {
                    int slideNumber = 1;
                    for (XSLFSlide slide : show.getSlides()) {
                        content.append("\n第 ").append(slideNumber++).append(" 页：\n");
                        for (XSLFShape shape : slide.getShapes()) {
                            if (shape instanceof XSLFTextShape textShape
                                    && !textShape.getText().isBlank()) {
                                content.append(textShape.getText().trim()).append('\n');
                            }
                        }
                    }
                }
            } else if (filename.endsWith(".ppt")) {
                try (HSLFSlideShow show = new HSLFSlideShow(input)) {
                    int slideNumber = 1;
                    for (HSLFSlide slide : show.getSlides()) {
                        content.append("\n第 ").append(slideNumber++).append(" 页：\n");
                        for (HSLFShape shape : slide.getShapes()) {
                            if (shape instanceof HSLFTextShape textShape
                                    && !textShape.getText().isBlank()) {
                                content.append(textShape.getText().trim()).append('\n');
                            }
                        }
                    }
                }
            } else {
                throw new IllegalArgumentException("仅支持 PPT 或 PPTX 文件");
            }
        }
        String extracted = content.toString().trim();
        if (extracted.isBlank()) {
            throw new IllegalArgumentException("PPT 中没有可识别的文本内容");
        }
        log.info("本地提取 PPT 文本完成: file={}, textLength={}", originalFilename,
                extracted.length());
        return extracted;
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
