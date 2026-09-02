package com.a09.tts.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Component
public class LoginRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final String KEY_PREFIX = "auth:login:";

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${app.security.login-rate-limit.max-failures:5}")
    private int maxFailures;

    @Value("${app.security.login-rate-limit.window:10m}")
    private Duration window;

    @Value("${app.security.login-rate-limit.lock-duration:15m}")
    private Duration lockDuration;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private LongSupplier clock = System::currentTimeMillis;

    public boolean isBlocked(String ip, String username) {
        String key = key(ip, username);
        if (redisEnabled && redisTemplate != null) {
            try {
                return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + "lock:" + key));
            } catch (RuntimeException exception) {
                log.warn("Redis 登录限流不可用，切换到进程内限流");
            }
        }
        long now = clock.getAsLong();
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return false;
        }
        if (attempt.lockedUntil > now) {
            return true;
        }
        if (attempt.lockedUntil > 0 || now - attempt.windowStartedAt >= window.toMillis()) {
            attempts.remove(key, attempt);
        }
        return false;
    }

    public void recordFailure(String ip, String username) {
        String key = key(ip, username);
        if (redisEnabled && redisTemplate != null) {
            try {
                String failureKey = KEY_PREFIX + "fail:" + key;
                Long failures = redisTemplate.opsForValue().increment(failureKey);
                if (failures != null && failures == 1) {
                    redisTemplate.expire(failureKey, window);
                }
                if (failures != null && failures >= maxFailures) {
                    redisTemplate.opsForValue().set(KEY_PREFIX + "lock:" + key, "1", lockDuration);
                    redisTemplate.delete(failureKey);
                }
                return;
            } catch (RuntimeException exception) {
                log.warn("Redis 登录限流写入失败，切换到进程内限流");
            }
        }
        long now = clock.getAsLong();
        attempts.compute(key, (ignored, current) -> {
            Attempt attempt = current;
            if (attempt == null || now - attempt.windowStartedAt >= window.toMillis()
                    || attempt.lockedUntil > 0 && attempt.lockedUntil <= now) {
                attempt = new Attempt(now);
            }
            attempt.failures++;
            if (attempt.failures >= maxFailures) {
                attempt.failures = 0;
                attempt.lockedUntil = now + lockDuration.toMillis();
            }
            return attempt;
        });
    }

    public void recordSuccess(String ip, String username) {
        String key = key(ip, username);
        attempts.remove(key);
        if (redisEnabled && redisTemplate != null) {
            try {
                redisTemplate.delete(KEY_PREFIX + "fail:" + key);
                redisTemplate.delete(KEY_PREFIX + "lock:" + key);
            } catch (RuntimeException exception) {
                log.warn("Redis 登录限流清理失败");
            }
        }
    }

    void configureForTest(int maxFailures, Duration window, Duration lockDuration, LongSupplier clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.lockDuration = lockDuration;
        this.clock = clock;
        this.redisEnabled = false;
    }

    private String key(String ip, String username) {
        String value = (ip == null ? "unknown" : ip) + "\n"
                + (username == null ? "" : username.trim().toLowerCase());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建登录限流键", exception);
        }
    }

    private static final class Attempt {
        private final long windowStartedAt;
        private int failures;
        private long lockedUntil;

        private Attempt(long windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }
    }
}
