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
        net.bramp.ffmpeg.FFmpeg ffmpeg = new net.bramp.ffmpeg.FFmpeg(ffmpegPath);
        net.bramp.ffmpeg.builder.FFmpegBuilder builder = new net.bramp.ffmpeg.builder.FFmpegBuilder()
                .setInput(videoPath)
                .addInput(newAudioPath)
                .addExtraArgs("-vf", "subtitles=" + subtitlePath.replace("\\", "/").replace(":", "\\:"))
                .overrideOutputFiles(true)
                .addOutput(outputVideoPath)
                .setAudioCodec("aac")
                .setFormat("mp4")
                .done();
        ffmpeg.run(builder);
        log.info("最终视频合成完成: {}", outputVideoPath);
        return outputVideoPath;
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
