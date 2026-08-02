package com.a09.tts.service.impl;

import com.a09.tts.service.DialogueSessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisDialogueSessionStore implements DialogueSessionStore {

    private static final String KEY_PREFIX = "zjys:dialogue:session:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> advanceDialogueScript;
    private final Duration ttl;

    public RedisDialogueSessionStore(
            StringRedisTemplate redisTemplate,
            RedisScript<List> advanceDialogueScript,
            @Value("${app.redis.dialogue-session-ttl:30m}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.advanceDialogueScript = advanceDialogueScript;
        this.ttl = ttl;
    }

    @Override
    public void create(String sessionId, String scenarioId, String username) {
        String key = key(sessionId);
        redisTemplate.opsForHash().putAll(key, Map.of(
                "scenarioId", scenarioId,
                "currentTurn", "0",
                "username", username
        ));
        redisTemplate.expire(key, ttl);
    }

    @Override
    public Optional<DialogueSession> advance(String sessionId, String username) {
        List<?> result = redisTemplate.execute(
                advanceDialogueScript,
                List.of(key(sessionId)),
                username,
                Long.toString(ttl.toMillis()));
        if (result == null || result.isEmpty()) {
            return Optional.empty();
        }
        if ("FORBIDDEN".equals(result.get(0).toString())) {
            throw new SecurityException("无权访问该对话会话");
        }
        if (result.size() != 4 || !"OK".equals(result.get(0).toString())) {
            throw new IllegalStateException("Redis返回了无效的对话会话状态");
        }
        return Optional.of(new DialogueSession(
                result.get(1).toString(),
                Integer.parseInt(result.get(2).toString()),
                result.get(3).toString()));
    }

    @Override
    public void delete(String sessionId) {
        redisTemplate.delete(key(sessionId));
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
