package com.a09.tts.service;

import org.springframework.http.ResponseEntity;

public interface VideoVoiceSwapService {

    ResponseEntity<byte[]> processVideo(String videoPath, String voiceType,
                                        double speed, double pitch, double rhythm) throws Exception;
}
