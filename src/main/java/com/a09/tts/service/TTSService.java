package com.a09.tts.service;

import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.OutputStream;

public interface TTSService {

    ResponseEntity<byte[]> tts(String text, String voice);

    ResponseEntity<byte[]> tts(String text, String voice, double speed, double pitch, double rhythm);

    void validate(String text, String voice);

    void stream(String text, String voice, double speed, double pitch, double rhythm,
                OutputStream outputStream) throws IOException;
}
