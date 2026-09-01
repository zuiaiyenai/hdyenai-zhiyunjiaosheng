package com.a09.tts.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TTSServiceImplLanguageTest {

    private final TTSServiceImpl service = new TTSServiceImpl(WebClient.builder());

    @TempDir
    Path sampleLibrary;

    @Test
    void textContentControlsLanguageForEveryBuiltInRole() {
        assertEquals("en", language("Hello classroom"));
        assertEquals("zh", language("欢迎来到课堂"));
    }

    @Test
    void customVoiceKeepsAutomaticLanguageDetection() {
        assertEquals("zh", language("欢迎来到课堂"));
        assertEquals("en", language("Hello classroom"));
    }

    @Test
    void englishRoleUsesIndependentEnglishReference() throws Exception {
        Files.createFile(sampleLibrary.resolve("Katherine_Maher_reference.wav"));
        ReflectionTestUtils.setField(service, "sampleLibraryPath", sampleLibrary.toString());
        ReflectionTestUtils.setField(service, "englishVoice", "Katherine_Maher_reference");

        Map<String, Object> request = ReflectionTestUtils.invokeMethod(
                service, "createRequest", "Welcome to class.", "longxiao-en", 1.0, 1.0, 1.0, false);

        assertEquals("en", request.get("text_lang"));
        assertEquals("en", request.get("prompt_lang"));
        assertEquals("Hi, my name is Katherine Maher. I am the executive director of Wikimedia Foundation.",
                request.get("prompt_text"));
        assertTrue(request.get("ref_audio_path").toString().endsWith("Katherine_Maher_reference.wav"));
    }

    @Test
    void englishRoleRejectsChineseText() throws Exception {
        Files.createFile(sampleLibrary.resolve("Katherine_Maher_reference.wav"));
        ReflectionTestUtils.setField(service, "sampleLibraryPath", sampleLibrary.toString());
        ReflectionTestUtils.setField(service, "englishVoice", "Katherine_Maher_reference");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "createRequest",
                        "爱玩原神", "longxiao-en", 1.0, 1.0, 1.0, true));

        assertEquals("请输入英文", exception.getMessage());
    }

    @Test
    void chineseRoleRejectsEnglishText() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "createRequest",
                        "Hello classroom", "longxiao", 1.0, 1.0, 1.0, true));

        assertEquals("请输入中文", exception.getMessage());
    }

    @Test
    void streamingRequestSplitsTextAtPunctuation() throws Exception {
        Files.createFile(sampleLibrary.resolve("样本.wav"));
        ReflectionTestUtils.setField(service, "sampleLibraryPath", sampleLibrary.toString());

        Map<String, Object> request = ReflectionTestUtils.invokeMethod(
                service, "createRequest", "第一句。第二句。", "样本", 1.0, 1.0, 1.0, true);

        assertEquals("cut5", request.get("text_split_method"));
        assertEquals(true, request.get("streaming_mode"));
    }

    private String language(String text) {
        return ReflectionTestUtils.invokeMethod(service, "resolveTextLanguage", text);
    }
}
