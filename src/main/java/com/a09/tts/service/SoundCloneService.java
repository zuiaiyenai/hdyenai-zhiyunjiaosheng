package com.a09.tts.service;

import org.springframework.http.ResponseEntity;

public interface SoundCloneService {

    ResponseEntity<byte[]> soundClone(
            String promptText, String promptLang, String text, String textLang, String audioFilePath);
}
