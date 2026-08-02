package com.a09.tts.service;

import org.springframework.http.ResponseEntity;

public interface TTSService {

    ResponseEntity<byte[]> tts(String text, String voice);

    ResponseEntity<byte[]> tts(String text, String voice, double speed, double pitch, double rhythm);
}
