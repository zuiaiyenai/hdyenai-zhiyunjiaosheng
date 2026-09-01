package com.a09.tts.service;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DialectTextEnhancer {
    private static final Map<String, String> NORTHEAST_REPLACEMENTS = replacements(
            "我们", "咱们",
            "大家", "大伙儿",
            "什么", "啥",
            "怎么", "咋",
            "非常", "贼",
            "不仅", "不光",
            "聊天", "唠嗑",
            "顺畅", "顺溜");

    private DialectTextEnhancer() { }

    public static String enhance(String text, String dialect) {
        if (!"东北话".equals(dialect)) {
            return text;
        }
        String enhanced = text;
        for (Map.Entry<String, String> replacement : NORTHEAST_REPLACEMENTS.entrySet()) {
            enhanced = enhanced.replace(replacement.getKey(), replacement.getValue());
        }
        return enhanced;
    }

    private static Map<String, String> replacements(String... pairs) {
        Map<String, String> replacements = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            replacements.put(pairs[index], pairs[index + 1]);
        }
        return replacements;
    }
}
