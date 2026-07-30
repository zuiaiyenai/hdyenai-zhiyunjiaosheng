package com.a09.tts.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextChunkUtils {
    // 定义用于分割文本的标点符号正则表达式
    private static final String PUNCTUATION_REGEX = "[。！？；;,.!?]";

    public static List<String> splitTextByPunctuation(String text) {
        List<String> chunks = new ArrayList<>();
        // 编译正则表达式
        Pattern pattern = Pattern.compile(PUNCTUATION_REGEX);
        Matcher matcher = pattern.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            int end = matcher.end();
            // 提取从上次分割位置到当前标点符号位置的文本块
            String chunk = text.substring(lastEnd, end);
            chunks.add(chunk);
            lastEnd = end;
        }
        // 处理最后一个标点符号之后的文本
        if (lastEnd < text.length()) {
            chunks.add(text.substring(lastEnd));
        }
        return chunks;
    }
}