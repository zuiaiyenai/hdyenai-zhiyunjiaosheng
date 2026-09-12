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
import java.util.function.LongSupplier;

@Component
public class UserEndpointRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(UserEndpointRateLimiter.class);
    private static final String KEY_PREFIX = "zjys:rate:user:";

    private final MeterRegistry meterRegistry;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    @Qualifier("fixedWindowRateLimitScript")
    private RedisScript<List> rateLimitScript;

    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${app.security.endpoint-rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.security.endpoint-rate-limit.max-requests:120}")
    private int maxRequests;

    @Value("${app.security.endpoint-rate-limit.window:1m}")
    private Duration window;

    @Value("${app.security.endpoint-rate-limit.fallback-max-entries:10000}")
    private int fallbackMaxEntries;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private LongSupplier clock = System::currentTimeMillis;

    public UserEndpointRateLimiter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void validateConfiguration() {
        if (maxRequests < 1 || window == null || window.isZero() || window.isNegative()
                || fallbackMaxEntries < 1) {
            throw new IllegalArgumentException("用户接口限流配置必须为正数");
        }
    }

    public Decision check(String username, String endpoint) {
        if (!enabled) {
            return Decision.allowed("disabled");
        }
        String key = key(username, endpoint);
        if (redisEnabled && redisTemplate != null && rateLimitScript != null) {
            try {
                List<?> result = redisTemplate.execute(rateLimitScript, List.of(KEY_PREFIX + key),
                        Integer.toString(maxRequests), Long.toString(window.toMillis()));
                if (result == null || result.size() != 3) {
                    throw new IllegalStateException("Redis 返回了无效的限流结果");
                }
                boolean allowed = Long.parseLong(result.get(0).toString()) == 1;
                Duration retryAfter = retryAfter(result.get(2));
                return decision(allowed, retryAfter, "redis");
            } catch (RuntimeException exception) {
                meterRegistry.counter("fctts.rate.limit.degraded", "scope", "user_endpoint").increment();
                log.warn("Redis 用户接口限流不可用，切换到有界进程内限流");
            }
        }
        return checkLocal(key);
    }

    void configureForTest(int maxRequests, Duration window, int fallbackMaxEntries,
                          LongSupplier clock) {
        this.enabled = true;
        this.redisEnabled = false;
        this.maxRequests = maxRequests;
        this.window = window;
        this.fallbackMaxEntries = fallbackMaxEntries;
        this.clock = clock;
    }

    private Decision checkLocal(String key) {
        long now = clock.getAsLong();
        Window current = windows.get(key);
        if (current == null && windows.size() >= fallbackMaxEntries) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= window.toMillis());
            if (windows.size() >= fallbackMaxEntries) {
                return decision(false, window, "local_fallback_capacity");
            }
        }
        Window updated = windows.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous.startedAt >= window.toMillis()) {
                return new Window(now, 1);
            }
            return new Window(previous.startedAt, previous.count + 1);
        });
        long remaining = Math.max(1, window.toMillis() - (now - updated.startedAt));
        return decision(updated.count <= maxRequests, Duration.ofMillis(remaining), "local_fallback");
    }

    private Decision decision(boolean allowed, Duration retryAfter, String backend) {
        if (!allowed) {
            meterRegistry.counter("fctts.rate.limit.rejected",
                    "scope", "user_endpoint", "backend", backend).increment();
        }
        return new Decision(allowed, retryAfter, backend);
    }

    private Duration retryAfter(Object ttl) {
        long millis = Long.parseLong(ttl.toString());
        return Duration.ofMillis(millis > 0 ? millis : window.toMillis());
    }

    private String key(String username, String endpoint) {
        String value = username.trim().toLowerCase() + "\n" + endpoint;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建用户接口限流键", exception);
        }
    }

    private record Window(long startedAt, int count) {
    }

    public record Decision(boolean allowed, Duration retryAfter, String backend) {
        private static Decision allowed(String backend) {
            return new Decision(true, Duration.ZERO, backend);
        }
    }
}
