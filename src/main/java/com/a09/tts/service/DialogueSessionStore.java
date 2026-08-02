package com.a09.tts.service;

import java.util.Optional;

public interface DialogueSessionStore {

    void create(String sessionId, String scenarioId, String username);

    Optional<DialogueSession> advance(String sessionId, String username);

    void delete(String sessionId);

    record DialogueSession(String scenarioId, int currentTurn, String username) {
    }
}
