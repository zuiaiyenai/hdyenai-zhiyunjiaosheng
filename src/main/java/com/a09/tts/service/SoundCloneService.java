package com.a09.tts.service;

import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

public interface SoundCloneService {

    ResponseEntity<StreamingResponseBody> soundClone(
            String promptText, String promptLang, String text, String textLang, String audioFilePath);
}
