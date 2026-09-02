package com.a09.tts.service;

import com.a09.tts.api.VideoSubtitlePreview;
import org.springframework.http.ResponseEntity;

public interface VideoVoiceSwapService {

    ResponseEntity<byte[]> processVideo(String videoPath, String voiceType,
                                        double speed, double pitch, double rhythm) throws Exception;

    ResponseEntity<byte[]> processVideo(String videoPath, String voiceType,
                                        double speed, double pitch, double rhythm,
                                        String transcript, String subtitles,
                                        boolean includeSubtitles) throws Exception;

    VideoSubtitlePreview generateSubtitlePreview(String videoPath) throws Exception;
}
