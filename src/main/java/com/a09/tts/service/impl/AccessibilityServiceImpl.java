package com.a09.tts.service.impl;

import com.a09.tts.service.ASRService;
import com.a09.tts.service.AccessibilityService;
import com.a09.tts.service.MoonshotChatClient;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AccessibilityServiceImpl implements AccessibilityService {

    private static final Logger log = LoggerFactory.getLogger(AccessibilityServiceImpl.class);

    @Value("${app.accessibility-dir:./uploads/accessibility}")
    private String accessibilityDir;

    @Autowired(required = false)
    private ASRService asrService;

    private final MoonshotChatClient moonshotChatClient;

    public AccessibilityServiceImpl(MoonshotChatClient moonshotChatClient) {
        this.moonshotChatClient = moonshotChatClient;
    }

    /**
     * 朗读上传的文本文件内容（TTS合成）
     */
    public Map<String, Object> readTextFile(MultipartFile file) throws Exception {
        Map<String, Object> result = new HashMap<>();
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        result.put("text", content);
        result.put("fileName", file.getOriginalFilename());
        result.put("textLength", content.length());
        result.put("message", "文件读取成功，已准备好进行语音合成朗读");
        log.info("文件朗读 - 文件名: {}, 字数: {}", file.getOriginalFilename(), content.length());
        return result;
    }

    /**
     * 语音笔记：使用ASR真实转写语音为文字并保存笔记
     */
    public Map<String, Object> saveVoiceNote(MultipartFile audioFile, String title) throws Exception {
        Map<String, Object> result = new HashMap<>();
        Path notesDir = Paths.get(accessibilityDir, "notes");
        Files.createDirectories(notesDir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String audioFileName = timestamp + "_" + audioFile.getOriginalFilename();
        Path audioPath = notesDir.resolve(audioFileName);
        Files.copy(audioFile.getInputStream(), audioPath);

        // 调用ASR进行真实的语音转文字
        String transcribedText = "";
        if (asrService != null) {
            try {
                transcribedText = asrService.transcribe(audioPath.toString(), "zh");
                log.info("语音笔记ASR转写结果: {}", transcribedText);
            } catch (Exception e) {
                log.warn("ASR转写失败，使用默认文本: {}", e.getMessage());
            }
        }

        if (transcribedText == null || transcribedText.isEmpty() || transcribedText.startsWith("[ASR服务暂不可用]")) {
            transcribedText = "[语音笔记] " + (title != null ? title : "未命名笔记")
                    + " - 录制时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    + "\n（注：ASR服务未连接，请确保语音识别服务已启动）";
        }

        // 保存笔记文本
        String noteFileName = timestamp + "_note.txt";
        Path notePath = notesDir.resolve(noteFileName);
        Files.writeString(notePath, transcribedText, StandardCharsets.UTF_8);

        result.put("noteId", timestamp);
        result.put("title", title);
        result.put("transcribedText", transcribedText);
        result.put("audioFilePath", audioPath.toString());
        result.put("noteFilePath", notePath.toString());
        result.put("message", "语音笔记保存成功");
        log.info("语音笔记已保存: {}", noteFileName);
        return result;
    }

    /**
     * 获取所有语音笔记列表
     */
    public Map<String, Object> listVoiceNotes() throws Exception {
        Map<String, Object> result = new HashMap<>();
        Path notesDir = Paths.get(accessibilityDir, "notes");
        if (!Files.exists(notesDir)) {
            result.put("notes", java.util.Collections.emptyList());
            result.put("message", "暂无语音笔记");
            return result;
        }

        List<Map<String, String>> notesList = new ArrayList<>();
        try (var stream = Files.list(notesDir)) {
            stream.filter(p -> p.toString().endsWith(".txt"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            Map<String, String> note = new HashMap<>();
                            note.put("fileName", p.getFileName().toString());
                            note.put("content", Files.readString(p, StandardCharsets.UTF_8));
                            notesList.add(note);
                        } catch (Exception e) {
                            log.warn("读取笔记文件失败: {}", p);
                        }
                    });
        }

        result.put("notes", notesList);
        result.put("total", notesList.size());
        result.put("message", "共找到 " + notesList.size() + " 条语音笔记");
        return result;
    }

    /**
     * 生成学习纪要 - 优先调用 Moonshot API，失败则使用本地生成
     */
    public Map<String, Object> generateStudySummary(String textContent) {
        Map<String, Object> result = new HashMap<>();
        String summary = null;
        boolean aiGenerated = false;

        // 尝试调用 Moonshot API
        if (moonshotChatClient.isConfigured()) {
            try {
                summary = callMoonshotApi(textContent);
                aiGenerated = true;
            } catch (Exception e) {
                log.warn("Moonshot API调用失败，使用本地生成: {}", e.getMessage());
            }
        }

        // API不可用时使用本地生成
        if (summary == null) {
            summary = generateLocalSummary(textContent);
        }

        result.put("summary", summary);
        result.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("source", aiGenerated ? "ai" : "local");
        return result;
    }

    /**
     * 调用 Moonshot/Kimi API 生成摘要
     */
    private String callMoonshotApi(String textContent) {
        log.info("调用Moonshot API生成摘要...");
        return moonshotChatClient.generate(
                "你是一个专业的学习助手。请根据用户提供的学习文本内容，生成一份结构清晰的学习纪要。" +
                        "要求包含：1. 内容概要（核心观点总结）2. 关键知识点（分点列出）" +
                        "3. 重点难点分析 4. 学习建议。格式使用Markdown。",
                "请为生成以下文本的学习纪要：\n\n" + textContent);
    }

    /**
     * 本地生成学习纪要（备用方案）
     */
    private String generateLocalSummary(String textContent) {
        StringBuilder summary = new StringBuilder();
        summary.append("# 学习纪要\n\n");
        summary.append("## 🚀 内容概要\n\n");
        String trimmed = textContent.length() > 500 ? textContent.substring(0, 500) + "..." : textContent;
        summary.append(trimmed).append("\n\n");

        summary.append("## 📳 基本信息\n\n");
        summary.append("- **总字数**: ").append(textContent.length()).append("字\n");
        summary.append("- **段落数**: ").append(textContent.split("\n").length).append("段\n");
        summary.append("- **句子数**: ").append(textContent.split("[。！？!?]").length).append("句\n");
        summary.append("- **生成时间**: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n\n");

        summary.append("## 📼 内容结构\n\n");
        String[] lines = textContent.split("\n");
        int headingCount = 0;
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.matches("^[\\d]+\\.\\s.*") || trimmedLine.matches("^#+\\s.*")) {
                summary.append("- ").append(trimmedLine).append("\n");
                headingCount++;
            }
        }
        if (headingCount == 0) {
            summary.append("（文本为连贯段落，无明显标题结构）\n");
        }

        summary.append("\n## 💡 学习建议\n\n");
        summary.append("1. **反复阅读**: 重点段落建议多读几遍，加深理解\n");
        summary.append("2. **做笔记**: 将关键知识点用自己的话记录下来\n");
        summary.append("3. **提问思考**: 对内容提出问题，培养批判性思维\n");
        summary.append("4. **实践应用**: 尝试将学到的知识应用到实际场景中\n");
        summary.append("5. **定期复习**: 建议24小时内复习第一次，一周内复习第二次\n");

        summary.append("\n---\n*由智韵教声AI学习助手自动生成*\n");
        return summary.toString();
    }

    /**
     * 朗读PPT文件（解析PPT内容并返回文本）
     */
    public Map<String, Object> readPPTFile(MultipartFile file) throws Exception {
        Map<String, Object> result = new HashMap<>();
        String fileName = file.getOriginalFilename();
        String content;
        if (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".ppt")) {
            content = extractPptText(file);
        } else {
            content = extractPptxText(file);
        }
        result.put("text", content);
        result.put("fileName", fileName);
        result.put("fileSize", file.getSize());
        result.put("message", "PPT文件解析成功");
        log.info("PPT文件朗读 - 文件名: {}, 提取字数: {}", fileName, content.length());
        return result;
    }

    private String extractPptxText(MultipartFile file) throws Exception {
        StringJoiner content = new StringJoiner("\n");
        try (XMLSlideShow slideShow = new XMLSlideShow(file.getInputStream())) {
            for (XSLFSlide slide : slideShow.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape && !textShape.getText().isBlank()) {
                        content.add(textShape.getText().trim());
                    }
                }
            }
        }
        return content.toString();
    }

    private String extractPptText(MultipartFile file) throws Exception {
        StringJoiner content = new StringJoiner("\n");
        try (HSLFSlideShow slideShow = new HSLFSlideShow(file.getInputStream())) {
            for (HSLFSlide slide : slideShow.getSlides()) {
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape && !textShape.getText().isBlank()) {
                        content.add(textShape.getText().trim());
                    }
                }
            }
        }
        return content.toString();
    }
}
