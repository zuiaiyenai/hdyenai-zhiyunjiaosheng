package com.a09.tts.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TTSServiceImplLanguageTest {

    private final TTSServiceImpl service = new TTSServiceImpl(WebClient.builder());

    @TempDir
    Path sampleLibrary;

    @Test
    void builtInRoleSelectionControlsTextLanguage() {
        assertEquals("zh", language("Hello classroom", "longxiao"));
        assertEquals("en", language("欢迎来到课堂", "longxiao-en"));
    }

    @Test
    void customVoiceKeepsAutomaticLanguageDetection() {
        assertEquals("zh", language("欢迎来到课堂", "样本"));
        assertEquals("en", language("Hello classroom", "样本"));
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

    private String language(String text, String voice) {
        return ReflectionTestUtils.invokeMethod(service, "resolveTextLanguage", text, voice);
    }
}
