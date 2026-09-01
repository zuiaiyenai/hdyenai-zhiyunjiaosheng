package com.a09.tts.service.impl;

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
import java.util.List;
import java.util.Locale;
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
        // 1. 提取音频
        String extractedAudioPath = extractAudio(videoPath);
        if (extractedAudioPath == null) {
            throw new Exception("音频提取失败，请确保 ffmpeg 已安装并配置正确");
        }

        // 2. ASR 语音识别
        String extractedText = asrService.transcribe(extractedAudioPath, "zh");
        log.info("视频ASR识别结果: {}", extractedText);

        // 3. TTS 语音合成
        ResponseEntity<byte[]> clonedAudioResponse = ttsService.tts(extractedText, voiceType);
        if (clonedAudioResponse.getBody() == null) {
            throw new Exception("TTS 语音合成失败，请确保TTS服务已启动");
        }

        // 4. 保存临时音频
        String clonedAudioPath = saveTempAudio(clonedAudioResponse.getBody());

        // 5. 生成字幕
        String subtitlePath = generateSubtitles(extractedText);

        // 6. 合并视频、音频、字幕
        String finalVideoPath = mergeAudioAndSubtitles(videoPath, clonedAudioPath, subtitlePath);

        return serveFile(finalVideoPath);
    }

    private String extractAudio(String videoPath) {
        try {
            String audioOutputPath = outputDir + "/extracted_audio.wav";
            Files.createDirectories(Paths.get(outputDir));
            net.bramp.ffmpeg.FFmpeg ffmpeg = new net.bramp.ffmpeg.FFmpeg(ffmpegPath);
            net.bramp.ffmpeg.builder.FFmpegBuilder builder = new net.bramp.ffmpeg.builder.FFmpegBuilder()
                    .setInput(videoPath)
                    .overrideOutputFiles(true)
                    .addOutput(audioOutputPath)
                    .setFormat("wav")
                    .done();
            ffmpeg.run(builder);
            log.info("音频提取成功: {}", audioOutputPath);
            return audioOutputPath;
        } catch (Exception e) {
            log.error("ffmpeg 音频提取失败，请确认 ffmpeg 已安装且路径正确: {}", e.getMessage());
            return null;
        }
    }

    private String mergeAudioAndSubtitles(String videoPath, String newAudioPath, String subtitlePath) throws Exception {
        String outputVideoPath = outputDir + "/final_output.mp4";
        Files.createDirectories(Paths.get(outputDir));
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
        return List.of(
                "-hide_banner", "-y",
                "-i", videoPath,
                "-i", newAudioPath,
                "-vf", "subtitles=" + subtitlePath.replace("\\", "/").replace(":", "\\:"),
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-filter:a", buildAtempoFilter(audioTempo),
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-shortest",
                "-movflags", "+faststart",
                outputVideoPath);
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

    private String saveTempAudio(byte[] audioData) throws Exception {
        Files.createDirectories(Paths.get(outputDir));
        String path = outputDir + "/cloned_audio.wav";
        Files.write(Paths.get(path), audioData);
        return path;
    }

    private String generateSubtitles(String text) throws Exception {
        Files.createDirectories(Paths.get(outputDir));
        String subtitlePath = outputDir + "/subtitles.srt";
        String srtContent = "1\n00:00:01,000 --> 00:00:05,000\n" + text;
        Files.write(Paths.get(subtitlePath), srtContent.getBytes(StandardCharsets.UTF_8));
        log.info("生成 SRT 字幕: {}", subtitlePath);
        return subtitlePath;
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
