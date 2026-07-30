package com.a09.tts.service;

import com.a09.tts.pojo.SpeakingPracticeHistory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

/**
 * 🌟 AI 口语实践服务 🌟
 * - 评分（流畅度、准确率）
 * - 记录学习历史数据（折线图）
 * - 生成个性化学习报告
 */
@Service
public class SpeakingPracticeService {

    // 存储历史数据（可连接数据库）
    private final SpeakingPracticeHistory history = new SpeakingPracticeHistory();

    /**
     * 提供示范文本 & AI 朗读音频
     */
    public ResponseEntity<?> getExampleTextAndAudio() {
        String exampleText = "Hello! Welcome to English speaking practice.";
        String exampleAudioUrl = "/static/audio/example.mp3";

        Map<String, String> responseData = new HashMap<>();
        responseData.put("example_text", exampleText);
        responseData.put("example_audio", exampleAudioUrl);

        return ResponseEntity.ok(responseData);
    }

    /**
     * 核心评测方法
     */
    public ResponseEntity<?> evaluate(String audioFilePath, String referenceText, String mode, String sessionId, String language) {
        try {
            // 🌟 模拟 AI 评分（实际项目中可接 ASR + 评分算法）
            double fluency = Math.random() * 20 + 80;
            double pronunciation = Math.random() * 20 + 75;
            double accuracy = Math.random() * 20 + 70;
            String mistakes = "hello, practice";

            // 🌟 计算正确率
            double correctnessRate = accuracy / 100 * 100;

            // 🌟 记录历史数据
            history.addRecord(fluency, pronunciation, accuracy);

            // 🌟 生成折线图数据
            Map<String, Object> historyData = new HashMap<>();
            historyData.put("fluency_trend", history.getFluencyScores());
            historyData.put("pronunciation_trend", history.getPronunciationScores());
            historyData.put("accuracy_trend", history.getAccuracyScores());

            // 🌟 生成个性化反馈
            String feedback = generateFeedback(fluency, pronunciation, accuracy);

            // 🌟 返回完整分析报告
            Map<String, Object> report = new HashMap<>();
            report.put("session_id", sessionId);
            report.put("fluency", fluency);
            report.put("pronunciation", pronunciation);
            report.put("accuracy", accuracy);
            report.put("correctness_rate", correctnessRate);
            report.put("mistakes", mistakes);
            report.put("feedback", feedback);
            report.put("history_data", historyData);

            return ResponseEntity.ok(report);

        } catch (Exception e) {
            return ResponseEntity.status(500).body("AI 评测失败：" + e.getMessage());
        }
    }

    /**
     * 🌟 生成个性化学习反馈
     */
    private String generateFeedback(double fluency, double pronunciation, double accuracy) {
        return String.format(
                "您的朗读表现不错！但可以提升发音准确度。当前流畅度 %.2f, 准确率 %.2f, 发音 %.2f。",
                fluency, accuracy, pronunciation
        );
    }
}