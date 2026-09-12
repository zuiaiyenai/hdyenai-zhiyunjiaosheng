package com.a09.tts.service.impl;

import com.a09.tts.service.SubtitleService;
import com.a09.tts.storage.ManagedObjectStorageService;
import com.a09.tts.storage.ObjectStorageKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class SubtitleServiceImpl implements SubtitleService {

    private static final Logger log = LoggerFactory.getLogger(SubtitleServiceImpl.class);
    private final ManagedObjectStorageService objectStorage;

    public SubtitleServiceImpl(ManagedObjectStorageService objectStorage) {
        this.objectStorage = objectStorage;
    }

    public String generateSubtitles(String audioObjectKey, String language, String owner)
            throws Exception {
        objectStorage.requireMetadata(owner, audioObjectKey);
        List<String> subtitles = recognizeSpeech(audioObjectKey, language);
        String subtitleObjectKey = ObjectStorageKeys.sibling(audioObjectKey, "subtitles.srt");
        byte[] content = String.join(System.lineSeparator(), subtitles)
                .getBytes(StandardCharsets.UTF_8);
        objectStorage.storeBytes(owner, subtitleObjectKey, content,
                "application/x-subrip; charset=UTF-8");
        log.info("自动生成字幕对象: {}", subtitleObjectKey);
        return subtitleObjectKey;
    }

    private List<String> recognizeSpeech(String audioFilePath, String language) {
        return List.of(
                "1\n00:00:01,000 --> 00:00:03,000\n你好，欢迎使用 AI 语音换声服务。",
                "2\n00:00:03,500 --> 00:00:06,000\n演示自动字幕生成"
        );
    }

}
