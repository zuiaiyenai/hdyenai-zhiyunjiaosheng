package com.a09.tts.service.impl;

import com.a09.tts.api.ServiceUnavailableException;
import com.a09.tts.task.TaskResourceBulkheads;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void longchengUsesVerifiedMaleReferencePrompt() throws Exception {
        Files.createFile(sampleLibrary.resolve("longcheng.mp3"));
        ReflectionTestUtils.setField(service, "sampleLibraryPath", sampleLibrary.toString());

        Map<String, Object> request = ReflectionTestUtils.invokeMethod(
                service, "createRequest", "欢迎来到课堂。", "longcheng", 1.0, 1.0, 1.0, false);

        assertEquals("zh", request.get("prompt_lang"));
        assertEquals("大家好，这是沉稳清晰的男生音色，适合知识讲解与课堂旁白。",
                request.get("prompt_text"));
        assertTrue(request.get("ref_audio_path").toString().endsWith("longcheng.mp3"));
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
        assertEquals(20, request.get("top_k"));
        assertEquals(0.6, request.get("top_p"));
        assertEquals(0.6, request.get("temperature"));
        assertEquals(0.3, request.get("fragment_interval"));
        assertFalse(request.containsKey("pitch"));
        assertFalse(request.containsKey("rhythm"));
    }

    @Test
    void slowGptSovitsFailsWithinConfiguredTimeout() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/tts", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(2_000);
                byte[] audio = new byte[]{'R', 'I', 'F', 'F'};
                exchange.sendResponseHeaders(200, audio.length);
                exchange.getResponseBody().write(audio);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            Files.createFile(sampleLibrary.resolve("样本.wav"));
            TTSServiceImpl bounded = new TTSServiceImpl(
                    WebClient.builder(), TaskResourceBulkheads.unrestricted(),
                    Duration.ofMillis(200));
            ReflectionTestUtils.setField(bounded, "apiUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/tts");
            ReflectionTestUtils.setField(bounded, "sampleLibraryPath", sampleLibrary.toString());

            long started = System.nanoTime();
            assertThrows(ServiceUnavailableException.class,
                    () -> bounded.tts("欢迎来到课堂", "样本"));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 1_500, "elapsedMillis=" + elapsedMillis);
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void streamWaitsForNextFragmentBeyondRegularRequestTimeout() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/tts", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "audio/wav");
                exchange.sendResponseHeaders(200, 262_144);
                for (int index = 0; index < 4; index++) {
                    exchange.getResponseBody().write(new byte[65_536]);
                    exchange.getResponseBody().flush();
                    Thread.sleep(400);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            Files.createFile(sampleLibrary.resolve("样本.wav"));
            TTSServiceImpl bounded = new TTSServiceImpl(
                    WebClient.builder(), TaskResourceBulkheads.unrestricted(),
                    Duration.ofMillis(200));
            ReflectionTestUtils.setField(bounded, "apiUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/tts");
            ReflectionTestUtils.setField(bounded, "sampleLibraryPath", sampleLibrary.toString());
            ReflectionTestUtils.setField(bounded, "streamingIdleTimeout", Duration.ofSeconds(2));

            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            bounded.stream("欢迎来到课堂", "样本", 1.0, 1.0, 1.0, audio);

            assertEquals(262_144, audio.size());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private String language(String text) {
        return ReflectionTestUtils.invokeMethod(service, "resolveTextLanguage", text);
    }
}
