package com.a09.tts.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class DialectVoiceCatalog {
    public record Voice(String id, String name, String dialect, String description) { }

    private static final List<Voice> VOICES = List.of(
            new Voice("cuijie", "翠姐", "东北话", "东北话女声"),
            new Voice("dahu", "大虎", "东北话", "东北话男声"),
            new Voice("shanshan", "姗姗", "粤语", "粤语女声，支持 24 kHz"),
            new Voice("jiajia", "佳佳", "粤语", "粤语女声"),
            new Voice("taozi", "桃子", "粤语", "粤语女声"),
            new Voice("kelly", "Kelly", "香港粤语", "香港粤语女声"),
            new Voice("chuangirl", "小玥", "四川话", "四川话女声"),
            new Voice("xiaoze", "小泽", "湖南话", "湖南重口音男声"),
            new Voice("aikan", "艾侃", "天津话", "天津话男声"),
            new Voice("qingqing", "青青", "台湾话", "中国台湾话女声"),
            new Voice("zhiqing", "知青", "台湾话", "中国台湾话女声（精品版）")
    );

    private DialectVoiceCatalog() { }

    public static List<Voice> voices() {
        return VOICES;
    }

    public static Map<String, List<Voice>> byDialect() {
        return VOICES.stream().collect(Collectors.groupingBy(
                Voice::dialect, java.util.LinkedHashMap::new, Collectors.toList()));
    }

    public static Voice require(String voiceId) {
        return VOICES.stream()
                .filter(voice -> voice.id().equals(voiceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的阿里云方言音色：" + voiceId));
    }
}
