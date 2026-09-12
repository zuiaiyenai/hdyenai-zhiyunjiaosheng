package com.a09.tts.security;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@Component
public class LoginRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final String KEY_PREFIX = "zjys:rate:login:";

    private final MeterRegistry meterRegistry;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    @Qualifier("loginFailureScript")
    private RedisScript<List> loginFailureScript;

    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${app.security.login-rate-limit.max-failures:5}")
    private int maxFailures;

    @Value("${app.security.login-rate-limit.window:10m}")
    private Duration window;

    @Value("${app.security.login-rate-limit.lock-duration:15m}")
    private Duration lockDuration;

    @Value("${app.security.login-rate-limit.fallback-max-entries:10000}")
    private int fallbackMaxEntries;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final AtomicLong fallbackBlockedUntil = new AtomicLong();
    private LongSupplier clock = System::currentTimeMillis;

    public LoginRateLimiter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void validateConfiguration() {
        if (maxFailures < 1 || window == null || window.isZero() || window.isNegative()
                || lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()
                || fallbackMaxEntries < 1) {
            throw new IllegalArgumentException("登录限流配置必须为正数");
        }
    }

    public boolean isBlocked(String ip) {
        String key = key(ip);
        if (redisAvailable()) {
            try {
                boolean blocked = Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(key)));
                if (blocked) {
                    rejected("redis");
                }
                return blocked;
            } catch (RuntimeException exception) {
                degraded();
                log.warn("Redis 登录限流不可用，切换到有界进程内限流");
            }
        }
        long now = clock.getAsLong();
        if (fallbackBlockedUntil.get() > now) {
            rejected("local_fallback");
            return true;
        }
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return false;
        }
        if (attempt.lockedUntil > now) {
            rejected("local_fallback");
            return true;
        }
        if (attempt.lockedUntil > 0 || now - attempt.windowStartedAt >= window.toMillis()) {
            attempts.remove(key, attempt);
        }
        return false;
    }

    public void recordFailure(String ip) {
        String key = key(ip);
        if (redisAvailable()) {
            try {
                redisTemplate.execute(loginFailureScript,
                        List.of(failureKey(key), lockKey(key)),
                        Integer.toString(maxFailures),
                        Long.toString(window.toMillis()),
                        Long.toString(lockDuration.toMillis()));
                return;
            } catch (RuntimeException exception) {
                degraded();
                log.warn("Redis 登录限流写入失败，切换到有界进程内限流");
            }
        }
        recordLocalFailure(key);
    }

    void configureForTest(int maxFailures, Duration window, Duration lockDuration,
                          int fallbackMaxEntries, LongSupplier clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.lockDuration = lockDuration;
        this.fallbackMaxEntries = fallbackMaxEntries;
        this.clock = clock;
        this.redisEnabled = false;
    }

    private boolean redisAvailable() {
        return redisEnabled && redisTemplate != null && loginFailureScript != null;
    }

    private void recordLocalFailure(String key) {
        long now = clock.getAsLong();
        if (!attempts.containsKey(key) && attempts.size() >= fallbackMaxEntries) {
            attempts.entrySet().removeIf(entry -> expired(entry.getValue(), now));
            if (attempts.size() >= fallbackMaxEntries) {
                fallbackBlockedUntil.set(now + window.toMillis());
                rejected("local_fallback_capacity");
                return;
            }
        }
        attempts.compute(key, (ignored, current) -> {
            Attempt attempt = current;
            if (attempt == null || expired(attempt, now)) {
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

    private boolean expired(Attempt attempt, long now) {
        return attempt.lockedUntil > 0 && attempt.lockedUntil <= now
                || attempt.lockedUntil == 0 && now - attempt.windowStartedAt >= window.toMillis();
    }

    private void rejected(String backend) {
        meterRegistry.counter("fctts.rate.limit.rejected",
                "scope", "login_ip", "backend", backend).increment();
    }

    private void degraded() {
        meterRegistry.counter("fctts.rate.limit.degraded", "scope", "login_ip").increment();
    }

    private String key(String ip) {
        String value = ip == null ? "unknown" : ip.trim().toLowerCase();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建登录限流键", exception);
        }
    }

    private String failureKey(String key) {
        return KEY_PREFIX + "fail:{" + key + "}";
    }

    private String lockKey(String key) {
        return KEY_PREFIX + "lock:{" + key + "}";
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
