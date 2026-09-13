package com.a09.tts.service;

import com.a09.tts.api.VideoSubtitlePreview;

import java.nio.file.Path;

public interface VideoVoiceSwapService {

    void processVideo(String videoPath, String voiceType,
                      double speed, double pitch, double rhythm,
                      Path outputPath) throws Exception;

    void processVideo(String videoPath, String voiceType,
                      double speed, double pitch, double rhythm,
                      String transcript, String subtitles,
                      boolean includeSubtitles, Path outputPath) throws Exception;

    VideoSubtitlePreview generateSubtitlePreview(String videoPath) throws Exception;
}
