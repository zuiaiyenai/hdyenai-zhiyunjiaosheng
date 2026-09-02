package com.a09.tts.service.impl;

import com.a09.tts.api.AsrResult;
import com.a09.tts.api.VideoSubtitlePreview;
import com.a09.tts.service.ASRService;
import com.a09.tts.service.TTSService;
import com.a09.tts.service.VideoVoiceSwapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class VideoVoiceSwapServiceImpl implements VideoVoiceSwapService {

    private static final Logger log = LoggerFactory.getLogger(VideoVoiceSwapServiceImpl.class);

    @Value("${app.video-dir:./uploads/video}")
    private String videoDir;

    @Value("${app.output-dir:./uploads/output}")
    private String outputDir;

    @Value("${app.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${app.ffprobe-path:ffprobe}")
    private String ffprobePath;

    private final ASRService asrService;
    private final TTSService ttsService;

    public VideoVoiceSwapServiceImpl(ASRService asrService, TTSService ttsService) {
        this.asrService = asrService;
        this.ttsService = ttsService;
    }

    public ResponseEntity<byte[]> processVideo(String videoPath, String voiceType,
                                                double speed, double pitch, double rhythm) throws Exception {
        return processVideo(videoPath, voiceType, speed, pitch, rhythm,
                null, null, true);
    }

    public ResponseEntity<byte[]> processVideo(String videoPath, String voiceType,
                                                double speed, double pitch, double rhythm,
                                                String transcript, String subtitles,
                                                boolean includeSubtitles) throws Exception {
        Path jobDir = createJobDirectory();
        try {
            AsrResult recognized = null;
            String extractedText = transcript == null ? "" : transcript.trim();
            if (extractedText.isBlank()) {
                String extractedAudioPath = requireExtractedAudio(videoPath, jobDir);
                recognized = asrService.transcribeDetailed(extractedAudioPath, "zh");
                extractedText = recognized.text().trim();
            }
            if (extractedText.isBlank()) {
                throw new IllegalArgumentException("视频中未识别到可用于换声的语音");
            }
            log.info("视频ASR识别结果: {}", extractedText);

            ResponseEntity<byte[]> clonedAudioResponse = ttsService.tts(extractedText, voiceType);
            if (!clonedAudioResponse.getStatusCode().is2xxSuccessful()
                    || clonedAudioResponse.getBody() == null
                    || clonedAudioResponse.getBody().length == 0) {
                throw new Exception("TTS 语音合成失败，请确保TTS服务已启动");
            }

            String clonedAudioPath = saveTempAudio(jobDir, clonedAudioResponse.getBody());
            String subtitlePath = null;
            if (includeSubtitles) {
                double duration = probeDuration(videoPath);
                String effectiveSubtitles = subtitles;
                if (effectiveSubtitles == null || effectiveSubtitles.isBlank()) {
                    AsrResult subtitleResult = recognized == null
                            ? new AsrResult(extractedText, null, null, null, List.of())
                            : recognized;
                    effectiveSubtitles = buildSrt(subtitleResult, duration);
                }
                subtitlePath = saveSubtitles(jobDir, effectiveSubtitles);
            }

            String finalVideoPath = mergeAudioAndSubtitles(
                    videoPath, clonedAudioPath, subtitlePath, jobDir);
            return serveFile(finalVideoPath);
        } finally {
            deleteJobDirectory(jobDir);
        }
    }

    public VideoSubtitlePreview generateSubtitlePreview(String videoPath) throws Exception {
        Path jobDir = createJobDirectory();
        try {
            String extractedAudioPath = requireExtractedAudio(videoPath, jobDir);
            AsrResult result = asrService.transcribeDetailed(extractedAudioPath, "zh");
            double duration = probeDuration(videoPath);
            boolean preciseTiming = hasUsableSegments(result, duration);
            return new VideoSubtitlePreview(
                    result.text(), buildSrt(result, duration), duration,
                    preciseTiming ? "asr" : "estimated");
        } finally {
            deleteJobDirectory(jobDir);
        }
    }

    private String requireExtractedAudio(String videoPath, Path jobDir) throws Exception {
        String extractedAudioPath = extractAudio(videoPath, jobDir);
        if (extractedAudioPath == null) {
            throw new Exception("音频提取失败，请确保 ffmpeg 已安装并配置正确");
        }
        return extractedAudioPath;
    }

    private String extractAudio(String videoPath, Path jobDir) {
        try {
            String audioOutputPath = jobDir.resolve("extracted_audio.wav").toString();
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.addAll(buildExtractCommand(videoPath, audioOutputPath));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String ffmpegOutput = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("ffmpeg 音频提取失败，FFmpeg 输出：{}", ffmpegOutput);
                return null;
            }
            log.info("音频提取成功: {}", audioOutputPath);
            return audioOutputPath;
        } catch (Exception e) {
            log.error("ffmpeg 音频提取失败，请确认 ffmpeg 已安装且路径正确: {}", e.getMessage());
            return null;
        }
    }

    List<String> buildExtractCommand(String videoPath, String audioOutputPath) {
        return List.of(
                "-hide_banner", "-y",
                "-i", videoPath,
                "-vn",
                "-ar", "16000",
                "-ac", "1",
                "-c:a", "pcm_s16le",
                audioOutputPath);
    }

    private String mergeAudioAndSubtitles(String videoPath, String newAudioPath,
                                          String subtitlePath, Path jobDir) throws Exception {
        String outputVideoPath = jobDir.resolve("final_output.mp4").toString();
        List<String> command = new ArrayList<>();
        double videoDuration = probeDuration(videoPath);
        double audioDuration = probeDuration(newAudioPath);
        double audioTempo = audioDuration / videoDuration;
        log.info("视频换声时长匹配：video={}s, audio={}s, tempo={}",
                videoDuration, audioDuration, audioTempo);
        command.add(ffmpegPath);
        command.addAll(buildMergeCommand(
                videoPath, newAudioPath, subtitlePath, outputVideoPath, audioTempo));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String ffmpegOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.error("视频合成失败，FFmpeg 输出：{}", ffmpegOutput);
            String detail = ffmpegOutput.lines().filter(line -> !line.isBlank())
                    .reduce((first, second) -> second).orElse("未知错误");
            throw new java.io.IOException("视频合成失败: " + detail);
        }
        log.info("最终视频合成完成: {}", outputVideoPath);
        return outputVideoPath;
    }

    List<String> buildMergeCommand(
            String videoPath, String newAudioPath, String subtitlePath,
            String outputVideoPath, double audioTempo) {
        List<String> command = new ArrayList<>(List.of(
                "-hide_banner", "-y",
                "-i", videoPath,
                "-i", newAudioPath));
        if (subtitlePath != null && !subtitlePath.isBlank()) {
            command.add("-vf");
            command.add("subtitles=" + subtitlePath.replace("\\", "/").replace(":", "\\:"));
        }
        command.addAll(List.of(
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-filter:a", buildAtempoFilter(audioTempo),
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-shortest",
                "-movflags", "+faststart",
                outputVideoPath));
        return command;
    }

    String buildAtempoFilter(double tempo) {
        if (!Double.isFinite(tempo) || tempo <= 0) {
            throw new IllegalArgumentException("音视频时长比例无效");
        }
        List<String> filters = new ArrayList<>();
        while (tempo > 2.0) {
            filters.add("atempo=2.000000");
            tempo /= 2.0;
        }
        while (tempo < 0.5) {
            filters.add("atempo=0.500000");
            tempo /= 0.5;
        }
        filters.add(String.format(Locale.ROOT, "atempo=%.6f", tempo));
        return String.join(",", filters);
    }

    private double probeDuration(String mediaPath) throws Exception {
        double duration = new net.bramp.ffmpeg.FFprobe(ffprobePath)
                .probe(mediaPath).getFormat().duration;
        if (!Double.isFinite(duration) || duration <= 0) {
            throw new IllegalArgumentException("无法读取媒体时长: " + mediaPath);
        }
        return duration;
    }

    private String saveTempAudio(Path jobDir, byte[] audioData) throws Exception {
        String path = jobDir.resolve("cloned_audio.wav").toString();
        Files.write(Paths.get(path), audioData);
        return path;
    }

    private String saveSubtitles(Path jobDir, String subtitles) throws Exception {
        String normalized = subtitles.replace("\r\n", "\n").trim();
        if (normalized.length() > 200_000 || !normalized.contains("-->")) {
            throw new IllegalArgumentException("字幕内容不是有效的 SRT 格式");
        }
        String subtitlePath = jobDir.resolve("subtitles.srt").toString();
        Files.writeString(Paths.get(subtitlePath), normalized + "\n", StandardCharsets.UTF_8);
        log.info("生成 SRT 字幕: {}", subtitlePath);
        return subtitlePath;
    }

    String buildSrt(AsrResult result, double durationSeconds) {
        if (result == null || result.text() == null || result.text().isBlank()) {
            throw new IllegalArgumentException("无法为无语音文本生成字幕");
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new IllegalArgumentException("视频时长无效");
        }
        List<AsrResult.Segment> segments = result.segments() == null
                ? List.of()
                : result.segments().stream()
                        .filter(segment -> segment != null
                                && segment.text() != null && !segment.text().isBlank()
                                && Double.isFinite(segment.startSeconds())
                                && Double.isFinite(segment.endSeconds())
                                && segment.startSeconds() >= 0
                                && segment.endSeconds() > segment.startSeconds()
                                && segment.startSeconds() < durationSeconds)
                        .toList();
        if (!segments.isEmpty()) {
            StringBuilder srt = new StringBuilder();
            for (int index = 0; index < segments.size(); index++) {
                AsrResult.Segment segment = segments.get(index);
                appendCue(srt, index + 1, segment.startSeconds(),
                        Math.min(segment.endSeconds(), durationSeconds), segment.text().trim());
            }
            return srt.toString();
        }

        List<String> sentences = List.of(result.text().trim().split(
                "(?<=[。！？!?；;])\\s*|\\R+")).stream()
                .map(String::trim)
                .filter(sentence -> !sentence.isBlank())
                .toList();
        if (sentences.isEmpty()) {
            sentences = List.of(result.text().trim());
        }
        int totalCharacters = sentences.stream()
                .mapToInt(sentence -> sentence.codePointCount(0, sentence.length()))
                .sum();
        StringBuilder srt = new StringBuilder();
        double start = 0;
        for (int index = 0; index < sentences.size(); index++) {
            String sentence = sentences.get(index);
            double end = index == sentences.size() - 1
                    ? durationSeconds
                    : start + durationSeconds
                            * sentence.codePointCount(0, sentence.length()) / totalCharacters;
            appendCue(srt, index + 1, start, end, sentence);
            start = end;
        }
        return srt.toString();
    }

    private boolean hasUsableSegments(AsrResult result, double durationSeconds) {
        return result != null && result.segments() != null && result.segments().stream()
                .anyMatch(segment -> segment != null
                        && segment.text() != null && !segment.text().isBlank()
                        && Double.isFinite(segment.startSeconds())
                        && Double.isFinite(segment.endSeconds())
                        && segment.startSeconds() >= 0
                        && segment.endSeconds() > segment.startSeconds()
                        && segment.startSeconds() < durationSeconds);
    }

    private void appendCue(StringBuilder srt, int index, double start, double end, String text) {
        srt.append(index).append('\n')
                .append(formatSrtTime(start)).append(" --> ").append(formatSrtTime(end)).append('\n')
                .append(text).append("\n\n");
    }

    private String formatSrtTime(double seconds) {
        long millis = Math.max(0, Math.round(seconds * 1000));
        long hours = millis / 3_600_000;
        long minutes = millis % 3_600_000 / 60_000;
        long wholeSeconds = millis % 60_000 / 1000;
        long remainder = millis % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
                hours, minutes, wholeSeconds, remainder);
    }

    private Path createJobDirectory() throws Exception {
        Path root = Paths.get(outputDir).normalize();
        Files.createDirectories(root);
        return Files.createDirectory(root.resolve("video-" + UUID.randomUUID()));
    }

    private void deleteJobDirectory(Path jobDir) {
        if (jobDir == null || !Files.exists(jobDir)) {
            return;
        }
        try (var paths = Files.walk(jobDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.warn("临时处理文件清理失败: {}", path, e);
                }
            });
        } catch (Exception e) {
            log.warn("临时任务目录清理失败: {}", jobDir, e);
        }
    }

    private ResponseEntity<byte[]> serveFile(String filePath) throws Exception {
        Path path = Paths.get(filePath);
        byte[] data = Files.readAllBytes(path);
        log.info("返回已处理视频: {}", filePath);
        return ResponseEntity.ok()
                .header("Content-Type", "video/mp4")
                .header("Content-Disposition", "attachment; filename=final_output.mp4")
                .body(data);
    }
}
