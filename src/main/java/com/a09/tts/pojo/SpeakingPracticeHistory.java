package com.a09.tts.pojo;

import java.util.ArrayList;
import java.util.List;

/**
 * 存储最近 5 次的口语评测数据（得分趋势分析）。
 */
public class SpeakingPracticeHistory {
    private final List<Double> fluencyScores = new ArrayList<>();
    private final List<Double> pronunciationScores = new ArrayList<>();
    private final List<Double> accuracyScores = new ArrayList<>();

    public void addRecord(double fluency, double pronunciation, double accuracy) {
        if (fluencyScores.size() >= 5) {
            fluencyScores.remove(0);
            pronunciationScores.remove(0);
            accuracyScores.remove(0);
        }
        fluencyScores.add(fluency);
        pronunciationScores.add(pronunciation);
        accuracyScores.add(accuracy);
    }

    public List<Double> getFluencyScores() {
        return fluencyScores;
    }

    public List<Double> getPronunciationScores() {
        return pronunciationScores;
    }

    public List<Double> getAccuracyScores() {
        return accuracyScores;
    }
}