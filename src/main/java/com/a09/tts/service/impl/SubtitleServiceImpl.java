package com.a09.tts.service.impl;

import com.a09.tts.service.SubtitleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
public class SubtitleServiceImpl implements SubtitleService {

    private static final Logger log = LoggerFactory.getLogger(SubtitleServiceImpl.class);

    public String generateSubtitles(String audioFilePath, String language) throws Exception {
        List<String> subtitles = recognizeSpeech(audioFilePath, language);
        String subtitleFilePath = audioFilePath.replace(".wav", ".srt");
        generateSRTFile(subtitleFilePath, subtitles);
        log.info("自动生成字幕: {}", subtitleFilePath);
        return subtitleFilePath;
    }

    private List<String> recognizeSpeech(String audioFilePath, String language) {
        return List.of(
                "1\n00:00:01,000 --> 00:00:03,000\n你好，欢迎使用 AI 语音换声服务。",
                "2\n00:00:03,500 --> 00:00:06,000\n演示自动字幕生成"
        );
    }

    private void generateSRTFile(String filePath, List<String> subtitles) throws Exception {
        Path path = Paths.get(filePath);
        Files.write(path, subtitles, StandardCharsets.UTF_8);
    }
}
