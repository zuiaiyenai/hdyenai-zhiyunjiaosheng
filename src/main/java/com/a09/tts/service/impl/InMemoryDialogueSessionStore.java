package com.a09.tts.service.impl;

import com.a09.tts.service.DialogueSessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryDialogueSessionStore implements DialogueSessionStore {

    private final ConcurrentMap<String, Entry> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;

    public InMemoryDialogueSessionStore(
            @Value("${app.redis.dialogue-session-ttl:30m}") Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public void create(String sessionId, String scenarioId, String username) {
        sessions.put(sessionId, new Entry(scenarioId, 0, username, expiresAt()));
    }

    @Override
    public Optional<DialogueSession> advance(String sessionId, String username) {
        AtomicReference<DialogueSession> result = new AtomicReference<>();
        sessions.computeIfPresent(sessionId, (key, entry) -> {
            if (entry.expiresAt().isBefore(Instant.now())) {
                return null;
            }
            if (!entry.username().equals(username)) {
                throw new SecurityException("无权访问该对话会话");
            }
            Entry advanced = new Entry(
                    entry.scenarioId(), entry.currentTurn() + 1, entry.username(), expiresAt());
            result.set(advanced.toSession());
            return advanced;
        });
        return Optional.ofNullable(result.get());
    }

    @Override
    public void delete(String sessionId) {
        sessions.remove(sessionId);
    }

    private Instant expiresAt() {
        return Instant.now().plus(ttl);
    }

    private record Entry(String scenarioId, int currentTurn, String username, Instant expiresAt) {
        private DialogueSession toSession() {
            return new DialogueSession(scenarioId, currentTurn, username);
        }
    }
}
