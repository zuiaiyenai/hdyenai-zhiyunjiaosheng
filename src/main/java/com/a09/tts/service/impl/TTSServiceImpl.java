package com.a09.tts.service.impl;

import com.a09.tts.api.ServiceUnavailableException;
import com.a09.tts.service.TTSService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class TTSServiceImpl implements TTSService {
    private static final Logger log = LoggerFactory.getLogger(TTSServiceImpl.class);
    private static final MediaType AUDIO_WAV = MediaType.parseMediaType("audio/wav");
    private static final Pattern SAFE_VOICE_NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,100}");
    private static final List<String> SUPPORTED_SAMPLE_EXTENSIONS = List.of(".m4a", ".wav", ".mp3", ".flac");
    private static final Map<String, String> REFERENCE_PROMPTS = Map.of(
            "红豆生南国", "红豆生南国，春来发几枝。",
            "样本", "你好，这里是智韵教声。",
            "Katherine_Maher_reference", "Hi, my name is Katherine Maher. I am the executive director of Wikimedia Foundation."
    );
    private final WebClient webClient;

    @Value("${tts.api.url}")
    private String apiUrl;

    @Value("${tts.sample-library-path}")
    private String sampleLibraryPath;

    @Value("${tts.default-voice:红豆生南国}")
    private String defaultVoice;

    @Value("${tts.english-voice:Katherine_Maher_reference}")
    private String englishVoice;

    public TTSServiceImpl(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }

    @Override
    public ResponseEntity<byte[]> tts(String text, String voice) {
        return tts(text, voice, 1.0, 1.0, 1.0);
    }

    @Override
    public ResponseEntity<byte[]> tts(String text, String voice, double speed, double pitch, double rhythm) {
        try {
            byte[] audio = audioFlux(createRequest(text, voice, speed, pitch, rhythm, false))
                    .reduce(new ArrayList<byte[]>(), (chunks, buffer) -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        chunks.add(bytes);
                        return chunks;
                    })
                    .map(this::join)
                    .block(Duration.ofMinutes(10));
            if (audio == null || audio.length == 0) {
                throw new ServiceUnavailableException("GPT-SoVITS 未返回音频");
            }

            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(AUDIO_WAV);
            responseHeaders.setContentDisposition(ContentDisposition.attachment().filename("speech.wav").build());
            return new ResponseEntity<>(audio, responseHeaders, HttpStatus.OK);
        } catch (ServiceUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("GPT-SoVITS call failed", exception);
            throw new ServiceUnavailableException("GPT-SoVITS 服务不可用", exception);
        }
    }

    @Override
    public void stream(String text, String voice, double speed, double pitch, double rhythm,
                       OutputStream outputStream) throws IOException {
        try {
            audioFlux(createRequest(text, voice, speed, pitch, rhythm, true))
                    .doOnNext(buffer -> {
                        try {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            outputStream.write(bytes);
                            outputStream.flush();
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        } finally {
                            DataBufferUtils.release(buffer);
                        }
                    })
                    .then()
                    .block(Duration.ofMinutes(10));
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        } catch (Exception exception) {
            log.error("GPT-SoVITS streaming call failed", exception);
            throw new IOException("GPT-SoVITS 流式合成失败", exception);
        }
    }

    private Flux<DataBuffer> audioFlux(Map<String, Object> request) {
        return webClient.post()
                .uri(apiUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(AUDIO_WAV)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("未知错误")
                        .map(message -> new ServiceUnavailableException("GPT-SoVITS 调用失败：" + message)))
                .bodyToFlux(DataBuffer.class);
    }

    private Map<String, Object> createRequest(String text, String voice, double speed,
                                               double pitch, double rhythm, boolean streaming) {
        validate(text, voice);
        ReferenceVoice reference = resolveReference(voice);
        return Map.ofEntries(
                Map.entry("text", text),
                Map.entry("text_lang", resolveTextLanguage(text, voice)),
                Map.entry("ref_audio_path", reference.path().toString().replace('\\', '/')),
                Map.entry("aux_ref_audio_paths", List.of()),
                Map.entry("prompt_lang", reference.promptLanguage()),
                Map.entry("prompt_text", reference.promptText()),
                Map.entry("top_k", 5),
                Map.entry("top_p", 0.9),
                Map.entry("temperature", 0.8),
                Map.entry("text_split_method", "cut0"),
                Map.entry("batch_size", 1),
                Map.entry("speed_factor", speed),
                Map.entry("pitch", pitch),
                Map.entry("rhythm", rhythm),
                Map.entry("media_type", "wav"),
                Map.entry("streaming_mode", streaming),
                Map.entry("fragment_interval", 0.12),
                Map.entry("parallel_infer", true),
                Map.entry("repetition_penalty", 1.35));
    }

    private ReferenceVoice resolveReference(String requestedVoice) {
        String voice = switch (requestedVoice) {
            case "default", "longxiao" -> defaultVoice;
            case "longxiao-en" -> englishVoice;
            default -> requestedVoice;
        };
        if (!SAFE_VOICE_NAME.matcher(stripExtension(voice)).matches()) {
            throw new IllegalArgumentException("音色名称不合法");
        }

        Path library = Path.of(sampleLibraryPath).toAbsolutePath().normalize();
        Path direct = library.resolve(voice).normalize();
        if (direct.startsWith(library) && Files.isRegularFile(direct)) {
            String name = stripExtension(direct.getFileName().toString());
            String promptText = referencePrompt(name);
            return new ReferenceVoice(direct, promptText, referencePromptLanguage(promptText));
        }
        for (String extension : SUPPORTED_SAMPLE_EXTENSIONS) {
            Path candidate = library.resolve(stripExtension(voice) + extension).normalize();
            if (candidate.startsWith(library) && Files.isRegularFile(candidate)) {
                String name = stripExtension(candidate.getFileName().toString());
                String promptText = referencePrompt(name);
                return new ReferenceVoice(candidate, promptText, referencePromptLanguage(promptText));
            }
        }
        throw new IllegalArgumentException("找不到音色参考文件：" + voice);
    }

    private void validate(String text, String voice) {
        if (text == null || text.isBlank() || text.length() > 5000) {
            throw new IllegalArgumentException("文本长度必须在 1 到 5000 字之间");
        }
        if (voice == null || voice.isBlank()) {
            throw new IllegalArgumentException("必须选择音色");
        }
    }

    private String resolveTextLanguage(String text, String voice) {
        return switch (voice) {
            case "longxiao" -> "zh";
            case "longxiao-en" -> "en";
            default -> containsChinese(text) ? "zh" : "en";
        };
    }

    private boolean containsChinese(String text) {
        return text.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String referencePrompt(String voiceName) {
        return REFERENCE_PROMPTS.getOrDefault(voiceName, voiceName);
    }

    private String referencePromptLanguage(String promptText) {
        return containsChinese(promptText) ? "zh" : "en";
    }

    private byte[] join(List<byte[]> chunks) {
        int size = chunks.stream().mapToInt(chunk -> chunk.length).sum();
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    private record ReferenceVoice(Path path, String promptText, String promptLanguage) {
    }
}
