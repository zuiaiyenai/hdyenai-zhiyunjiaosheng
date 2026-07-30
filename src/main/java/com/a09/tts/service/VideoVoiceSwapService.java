package com.a09.tts.service;

import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class VideoVoiceSwapService {

    @Value("${app.video-dir}")
    private String videoDir;

    @Value("${app.output-dir}")
    private String outputDir;

    private final ASRService asrService;
    private final TTSService ttsService;
    private Logger log;

    public VideoVoiceSwapService(ASRService asrService, TTSService ttsService) {
        this.asrService = asrService;
        this.ttsService = ttsService;
    }

    /**
     * 🎬 处理视频换声 + 自动字幕
     */
    public ResponseEntity<byte[]> processVideo(String videoPath, String voiceType, double speed, double pitch, double rhythm) throws Exception {
        // 1️⃣ **提取视频音频**
        String extractedAudioPath = extractAudio(videoPath);

        // 2️⃣ **ASR 转换为文本**
        String extractedText = asrService.transcribe(extractedAudioPath, "zh");

        // 3️⃣ **生成 AI 语音**
        ResponseEntity<byte[]> clonedAudioResponse = ttsService.tts(extractedText, voiceType);

        if (clonedAudioResponse.getBody() == null) {
            throw new Exception("🚨 TTS 语音合成失败");
        }

        // 4️⃣ **保存新合成的音频**
        String clonedAudioPath = saveTempAudio(clonedAudioResponse.getBody());

        // 5️⃣ **创建自动字幕**
        String subtitlePath = generateSubtitles(extractedText);

        // 6️⃣ **合成最终视频（替换音轨 & 添加字幕）**
        String finalVideoPath = mergeAudioAndSubtitles(videoPath, clonedAudioPath, subtitlePath);

        // 🔥 **返回处理后的视频**
        return serveFile(finalVideoPath);
    }

    /**
     * 🛠 **FFmpeg 提取视频中的音频**
     */
    private String extractAudio(String videoPath) throws Exception {
        String audioOutputPath = outputDir + "/extracted_audio.wav";
        FFmpeg ffmpeg = new FFmpeg("/usr/bin/ffmpeg");

        FFmpegBuilder builder = new FFmpegBuilder()
                .setInput(videoPath)
                .overrideOutputFiles(true)
                .addOutput(audioOutputPath)
                .setFormat("wav")
                .done();

        ffmpeg.run(builder);
        log.info("🎵 音频提取成功: {}", audioOutputPath);

        return audioOutputPath;
    }

    /**
     * 🎞 **合成视频，加入新音轨 & 字幕**
     */
    private String mergeAudioAndSubtitles(String videoPath, String newAudioPath, String subtitlePath) throws Exception {
        String outputVideoPath = outputDir + "/final_output.mp4";
        FFmpeg ffmpeg = new FFmpeg("/usr/bin/ffmpeg");

        FFmpegBuilder builder = new FFmpegBuilder()
                .setInput(videoPath)
                .addInput(newAudioPath)
                .addExtraArgs("-vf", "subtitles=" + subtitlePath)
                .overrideOutputFiles(true)
                .addOutput(outputVideoPath)
                .setAudioCodec("aac")
                .setFormat("mp4")
                .done();

        ffmpeg.run(builder);
        log.info("🎬 最终视频合成完成: {}", outputVideoPath);

        return outputVideoPath;
    }

    /**
     * 💾 **保存 TTS 生成的音频**
     */
    private String saveTempAudio(byte[] audioData) throws Exception {
        String path = outputDir + "/cloned_audio.wav";
        Path audioPath = Paths.get(path);
        Files.createDirectories(audioPath.getParent());
        Files.write(audioPath, audioData);
        return path;
    }

    /**
     * 🎥 **自动生成字幕**
     */
    private String generateSubtitles(String text) throws Exception {
        String subtitlePath = outputDir + "/subtitles.srt";
        String srtContent = "1\n00:00:01,000 --> 00:00:05,000\n" + text;
        Files.write(Paths.get(subtitlePath), srtContent.getBytes());
        log.info("📄 生成 SRT 字幕: {}", subtitlePath);
        return subtitlePath;
    }

    /**
     * 📤 **返回处理后的视频**
     */
    private ResponseEntity<byte[]> serveFile(String filePath) throws Exception {
        Path path = Paths.get(filePath);
        byte[] data = Files.readAllBytes(path);
        log.info("📡 返回已处理视频: {}", filePath);
        return ResponseEntity.ok().header("Content-Type", "video/mp4").body(data);
    }
}