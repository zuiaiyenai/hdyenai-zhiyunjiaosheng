package com.a09.tts.service.impl;

import com.a09.tts.pojo.SpeakingPracticeHistory;
import com.a09.tts.service.ASRService;
import com.a09.tts.service.DialogueSessionStore;
import com.a09.tts.service.SpeakingPracticeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * AI 口语实践服务
 * - 基于 ASR 的真实语音识别评测（非随机评分）
 * - 记录学习历史数据（数据库持久化）
 * - 生成个性化学习报告
 * - 多轮对话练习
 */
@Service
public class SpeakingPracticeServiceImpl implements SpeakingPracticeService {

    private static final Logger log = LoggerFactory.getLogger(SpeakingPracticeServiceImpl.class);
    private final DialogueSessionStore dialogueSessionStore;

    public SpeakingPracticeServiceImpl(DialogueSessionStore dialogueSessionStore) {
        this.dialogueSessionStore = dialogueSessionStore;
    }

    @Autowired(required = false)
    private ASRService asrService;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Value("${app.speaking-dir:uploads/speaking}")
    private String speakingDir;

    // 无数据库模式下按用户隔离历史，避免单例 Service 将不同用户数据混在一起。
    private final Map<String, SpeakingPracticeHistory> historyByUser = new java.util.concurrent.ConcurrentHashMap<>();

    // 多轮对话场景
    private static final Map<String, String[]> DIALOGUE_SCENARIOS = new LinkedHashMap<>();
    static {
        DIALOGUE_SCENARIOS.put("greeting", new String[]{
            "你好！今天天气真不错，你最近在忙什么？",
            "我最近在准备期末考试，课程内容比较多。",
            "加油！你觉得哪门课最难？",
            "我觉得数学比较难，特别是微积分部分。",
            "多做题会好很多，需要我帮你找一些练习题吗？"
        });
        DIALOGUE_SCENARIOS.put("shopping", new String[]{
            "欢迎光临！请问您想买点什么？",
            "我想买一件衬衫，有什么推荐吗？",
            "这件蓝色的怎么样？纯棉材质，穿着很舒适。",
            "看起来不错，多少钱？有折扣吗？",
            "原价299元，现在打八折，只要239元。"
        });
        DIALOGUE_SCENARIOS.put("travel", new String[]{
            "你好，我想了解一下去北京的旅行团。",
            "我们有多种行程可选，三日游和五日游。",
            "五日游的行程是怎样的？都包含哪些景点？",
            "包含故宫、长城、颐和园等经典景点。",
            "听起来不错，价格是多少？包含食宿吗？"
        });
        DIALOGUE_SCENARIOS.put("english_intro", new String[]{
            "Hello! Welcome to our speaking practice session.",
            "Could you please introduce yourself briefly?",
            "What are your hobbies and interests?",
            "That sounds great! Let's practice more.",
            "Excellent work! Keep practicing every day."
        });
    }

    /**
     * 提供示范文本 & AI 朗读音频
     */
    public ResponseEntity<?> getExampleTextAndAudio() {
        String exampleText = "欢迎来到智韵教声口语练习！请跟着示范朗读以下文本。" +
                "注意发音的准确性和语调的自然流畅。多练习可以提高你的口语水平。";
        String exampleAudioUrl = "/static/audio/example.mp3";

        Map<String, String> responseData = new HashMap<>();
        responseData.put("example_text", exampleText);
        responseData.put("example_audio", exampleAudioUrl);
        responseData.put("scenarios", String.join(",", DIALOGUE_SCENARIOS.keySet()));

        return ResponseEntity.ok(responseData);
    }

    /**
     * 核心评测方法 - 基于 ASR 真实语音识别
     */
    public ResponseEntity<?> evaluate(String audioFilePath, String referenceText, String mode,
                                      String sessionId, String language, String username) {
        try {
            String owner = normalizeUsername(username);
            SpeakingPracticeHistory history = historyByUser.computeIfAbsent(
                    owner, ignored -> new SpeakingPracticeHistory());
            if (asrService == null) {
                log.error("ASR 服务未配置，无法进行口语评测");
                return recognitionFailure(HttpStatus.SERVICE_UNAVAILABLE, "ASR_UNAVAILABLE",
                        "语音识别服务未配置，请启动本地 FunASR 后重试");
            }

            String userSpeech;
            try {
                userSpeech = asrService.transcribe(audioFilePath, language);
                log.info("ASR 识别结果: {}", userSpeech);
            } catch (Exception e) {
                log.error("ASR 服务调用失败，终止本次评测: {}", e.getMessage());
                return recognitionFailure(HttpStatus.SERVICE_UNAVAILABLE, "ASR_UNAVAILABLE",
                        "语音识别服务不可用，请确认本地 FunASR 已启动后重试");
            }

            if (userSpeech == null || userSpeech.isBlank() || userSpeech.startsWith("[")) {
                return recognitionFailure(HttpStatus.UNPROCESSABLE_ENTITY, "NO_SPEECH",
                        "未识别到有效语音，请在安静环境中重新录音");
            }
            userSpeech = userSpeech.trim();

            // 基于文本对比的真实评分
            double accuracy = calculateAccuracy(referenceText, userSpeech);
            double fluency = calculateFluency(userSpeech, language);
            double pronunciation = calculatePronunciation(accuracy, fluency);
            String mistakes = findMistakes(referenceText, userSpeech);

            double correctnessRate = accuracy;

            // 记录历史数据（内存）
            history.addRecord(fluency, pronunciation, accuracy);

            // 持久化到数据库
            saveHistoryToDB(sessionId, owner, referenceText, userSpeech, fluency, pronunciation,
                    accuracy, correctnessRate, mistakes, mode, language);

            // 生成折线图数据
            Map<String, Object> historyData = new HashMap<>();
            historyData.put("fluency_trend", history.getFluencyScores());
            historyData.put("pronunciation_trend", history.getPronunciationScores());
            historyData.put("accuracy_trend", history.getAccuracyScores());

            // 生成个性化反馈
            String feedback = generateFeedback(fluency, pronunciation, accuracy, mistakes);

            // 返回完整分析报告
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("session_id", sessionId);
            report.put("fluency", Math.round(fluency * 100.0) / 100.0);
            report.put("pronunciation", Math.round(pronunciation * 100.0) / 100.0);
            report.put("accuracy", Math.round(accuracy * 100.0) / 100.0);
            report.put("correctness_rate", Math.round(correctnessRate * 100.0) / 100.0);
            report.put("user_speech", userSpeech.length() > 100 ? userSpeech.substring(0, 100) + "..." : userSpeech);
            report.put("mistakes", mistakes);
            report.put("feedback", feedback);
            report.put("history_data", historyData);

            return ResponseEntity.ok(report);

        } catch (Exception e) {
            log.error("口语评测失败", e);
            return ResponseEntity.status(500).body("评测失败：" + e.getMessage());
        }
    }

    /**
     * 计算准确率 - 基于文本相似度
     */
    private double calculateAccuracy(String reference, String userText) {
        if (userText == null || userText.isEmpty() || userText.startsWith("[")) {
            return 0;
        }

        String refClean = reference.replaceAll("[\\s\\p{P}]", "");
        String userClean = userText.replaceAll("[\\s\\p{P}]", "");

        if (refClean.isEmpty()) return 50.0;

        // 计算字符级匹配度（简单Levenshtein-based）
        int matchCount = 0;
        String ref = refClean.toLowerCase();
        String user = userClean.toLowerCase();

        // 计算最长公共子序列长度
        int lcsLen = longestCommonSubsequence(ref, user);
        double lcsAccuracy = (double) lcsLen / Math.max(ref.length(), 1) * 100;

        // 计算单词匹配率
        String[] refWords = ref.split("");
        String[] userWords = user.split("");

        // 约束在合理范围内
        return Math.min(98, Math.max(40, lcsAccuracy));
    }

    /**
     * 最长公共子序列
     */
    private int longestCommonSubsequence(String a, String b) {
        int m = a.length(), n = b.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp[m][n];
    }

    /**
     * 计算流利度
     */
    private double calculateFluency(String userText, String language) {
        if (userText == null || userText.isEmpty() || userText.startsWith("[")) {
            return 0;
        }
        // 基于文本长度估算流利度
        int length = userText.length();
        if (length < 10) return 50.0;
        if (length < 30) return 65.0;
        if (length < 60) return 78.0;
        return 90.0;
    }

    private ResponseEntity<?> recognitionFailure(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "recognition_failed");
        body.put("code", code);
        body.put("message", message);
        body.put("user_speech", "[无法识别语音内容]");
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 计算发音分数
     */
    private double calculatePronunciation(double accuracy, double fluency) {
        return accuracy * 0.6 + fluency * 0.4;
    }

    /**
     * 查找错误部分
     */
    private String findMistakes(String reference, String userText) {
        if (userText == null || userText.isEmpty() || userText.startsWith("[")) {
            return "无法检测到有效语音";
        }

        // 简单对比找出不同字符
        StringBuilder mistakes = new StringBuilder();
        String ref = reference.replaceAll("[\\s\\p{P}]", "");
        String user = userText.replaceAll("[\\s\\p{P}]", "");

        int minLen = Math.min(ref.length(), user.length());
        for (int i = 0; i < minLen; i++) {
            if (ref.charAt(i) != user.charAt(i)) {
                if (mistakes.length() > 0) mistakes.append(", ");
                mistakes.append("位置").append(i + 1)
                        .append(": 期望「").append(ref.charAt(i))
                        .append("」实际「").append(user.charAt(i)).append("」");
                if (mistakes.length() > 200) break;
            }
        }

        String result = mistakes.length() > 0 ? mistakes.toString() : "发音准确";
        return result;
    }

    /**
     * 保存评测历史到数据库
     */
    private void saveHistoryToDB(String sessionId, String username, String referenceText, String userText,
                                  double fluency, double pronunciation, double accuracy,
                                  double correctnessRate, String mistakes, String mode, String language) {
        if (jdbcTemplate == null) return;
        try {
            jdbcTemplate.update(
                "INSERT INTO speaking_history (session_id, username, reference_text, user_text, fluency_score, " +
                "pronunciation_score, accuracy_score, correctness_rate, mistakes, feedback, mode, language) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, username, referenceText, userText, fluency, pronunciation, accuracy,
                correctnessRate, mistakes, generateFeedback(fluency, pronunciation, accuracy, mistakes),
                mode, language
            );
            log.info("评测历史已保存到数据库, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.warn("保存评测历史到数据库失败: {}", e.getMessage());
        }
    }

    /**
     * 获取历史评测记录
     */
    public ResponseEntity<?> getHistory(String sessionId, String username) {
        String owner = normalizeUsername(username);
        SpeakingPracticeHistory history = historyByUser.computeIfAbsent(
                owner, ignored -> new SpeakingPracticeHistory());
        if (jdbcTemplate == null) {
            return ResponseEntity.ok(Map.of("history", history, "message", "数据库未连接，仅返回内存数据"));
        }
        try {
            List<Map<String, Object>> records;
            if (sessionId == null || sessionId.isBlank()) {
                records = jdbcTemplate.queryForList(
                        "SELECT * FROM speaking_history WHERE username = ? ORDER BY created_at DESC LIMIT 20",
                        owner);
            } else {
                records = jdbcTemplate.queryForList(
                        "SELECT * FROM speaking_history WHERE session_id = ? AND username = ? " +
                                "ORDER BY created_at DESC LIMIT 20",
                        sessionId, owner);
            }
            return ResponseEntity.ok(Map.of("history", records, "total", records.size()));
        } catch (Exception e) {
            log.warn("获取历史记录失败: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("history", history, "message", e.getMessage()));
        }
    }

    private String normalizeUsername(String username) {
        return username == null || username.isBlank() ? "anonymous" : username;
    }

    /**
     * 获取可用对话场景列表
     */
    public ResponseEntity<?> getDialogueScenarios() {
        List<Map<String, String>> scenarios = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : DIALOGUE_SCENARIOS.entrySet()) {
            Map<String, String> s = new HashMap<>();
            s.put("id", entry.getKey());
            s.put("title", getScenarioTitle(entry.getKey()));
            s.put("firstLine", entry.getValue()[0]);
            scenarios.add(s);
        }
        return ResponseEntity.ok(scenarios);
    }

    private String getScenarioTitle(String id) {
        Map<String, String> titles = new HashMap<>();
        titles.put("greeting", "日常问候");
        titles.put("shopping", "购物对话");
        titles.put("travel", "旅游咨询");
        titles.put("english_intro", "英语自我介绍");
        return titles.getOrDefault(id, id);
    }

    /**
     * 获取对话场景的完整对话流
     */
    public ResponseEntity<?> startDialogue(String scenarioId, String username) {
        String[] lines = DIALOGUE_SCENARIOS.get(scenarioId);
        if (lines == null) {
            return ResponseEntity.badRequest().body("场景不存在: " + scenarioId);
        }
        String sessionId = UUID.randomUUID().toString();
        dialogueSessionStore.create(sessionId, scenarioId, username);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        result.put("scenario_id", scenarioId);
        result.put("scenario_title", getScenarioTitle(scenarioId));
        result.put("total_turns", lines.length);
        result.put("current_turn", 0);
        result.put("ai_message", lines[0]);
        return ResponseEntity.ok(result);
    }

    /**
     * 继续对话
     */
    public ResponseEntity<?> continueDialogue(String sessionId, String username) {
        DialogueSessionStore.DialogueSession session = dialogueSessionStore
                .advance(sessionId, username)
                .orElse(null);
        if (session == null) {
            return ResponseEntity.badRequest().body("对话会话不存在或已过期");
        }

        String scenarioId = session.scenarioId();
        String[] lines = DIALOGUE_SCENARIOS.get(scenarioId);
        if (lines == null) {
            dialogueSessionStore.delete(sessionId);
            return ResponseEntity.badRequest().body("场景不存在");
        }
        int nextTurn = session.currentTurn();
        if (nextTurn >= lines.length) {
            dialogueSessionStore.delete(sessionId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("session_id", sessionId);
            result.put("message", "对话已结束！");
            result.put("is_completed", true);
            return ResponseEntity.ok(result);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        result.put("scenario_id", scenarioId);
        result.put("current_turn", nextTurn);
        result.put("total_turns", lines.length);
        result.put("ai_message", lines[nextTurn]);
        result.put("is_completed", nextTurn >= lines.length - 1);
        return ResponseEntity.ok(result);
    }

    /**
     * 生成个性化学习反馈
     */
    private String generateFeedback(double fluency, double pronunciation, double accuracy, String mistakes) {
        StringBuilder feedback = new StringBuilder();
        feedback.append("📊 口语评测反馈\n\n");

        double overall = (fluency + pronunciation + accuracy) / 3.0;
        if (overall >= 85) {
            feedback.append("🎉 总体表现优秀！你的口语能力很不错。\n");
        } else if (overall >= 70) {
            feedback.append("👍 总体表现良好，继续加油！\n");
        } else if (overall >= 55) {
            feedback.append("📝 有一定基础，但还需要多加练习。\n");
        } else {
            feedback.append("💪 别灰心，多练习会进步的！\n");
        }

        feedback.append("\n📈 各项指标分析：\n");
        feedback.append(String.format("- 流利度: %.1f分", fluency));
        if (fluency >= 80) feedback.append(" ✅ 流利自然");
        else if (fluency >= 60) feedback.append(" ⚠️ 可以更流畅");
        else feedback.append(" ❌ 需要加强流畅度练习");
        feedback.append("\n");

        feedback.append(String.format("- 发音: %.1f分", pronunciation));
        if (pronunciation >= 80) feedback.append(" ✅ 发音清晰");
        else if (pronunciation >= 60) feedback.append(" ⚠️ 注意个别发音");
        else feedback.append(" ❌ 需要加强发音练习");
        feedback.append("\n");

        feedback.append(String.format("- 准确率: %.1f分", accuracy));
        if (accuracy >= 80) feedback.append(" ✅ 内容准确");
        else if (accuracy >= 60) feedback.append(" ⚠️ 部分不匹配");
        else feedback.append(" ❌ 需要对照原文练习");
        feedback.append("\n");

        feedback.append("\n💡 改进建议：\n");
        if (fluency < 70) feedback.append("- 多朗读短文，提升语感\n");
        if (pronunciation < 70) feedback.append("- 注意每个音节的发音清晰度\n");
        if (accuracy < 70) feedback.append("- 仔细对照原文，注意用词准确\n");
        if (mistakes != null && !mistakes.isEmpty() && !mistakes.equals("发音准确") && !mistakes.equals("无法检测到有效语音")) {
            feedback.append("- 重点关注出错部分的练习\n");
        }
        feedback.append("- 建议每天练习15-30分钟\n");

        return feedback.toString();
    }
}
