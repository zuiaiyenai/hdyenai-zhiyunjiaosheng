package com.a09.tts.service;

import org.springframework.http.ResponseEntity;

public interface SpeakingPracticeService {

    ResponseEntity<?> getExampleTextAndAudio();

    ResponseEntity<?> evaluate(String audioFilePath, String referenceText, String mode,
                               String sessionId, String language, String username);

    ResponseEntity<?> getHistory(String sessionId, String username);

    ResponseEntity<?> getDialogueScenarios();

    ResponseEntity<?> startDialogue(String scenarioId, String username);

    ResponseEntity<?> continueDialogue(String sessionId, String username);
}
