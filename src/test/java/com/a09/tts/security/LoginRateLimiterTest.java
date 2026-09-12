package com.a09.tts.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {
    @Test
    void locksByIpAndExpires() {
        AtomicLong now = new AtomicLong(1_000);
        LoginRateLimiter limiter = new LoginRateLimiter(new SimpleMeterRegistry());
        limiter.configureForTest(3, Duration.ofMinutes(1), Duration.ofMinutes(5), 100, now::get);

        limiter.recordFailure("127.0.0.1");
        limiter.recordFailure("127.0.0.1");
        assertFalse(limiter.isBlocked("127.0.0.1"));
        limiter.recordFailure("127.0.0.1");
        assertTrue(limiter.isBlocked("127.0.0.1"));
        assertFalse(limiter.isBlocked("127.0.0.2"));

        now.addAndGet(Duration.ofMinutes(5).toMillis() + 1);
        assertFalse(limiter.isBlocked("127.0.0.1"));
    }

    @Test
    void boundedFallbackFailsClosedWhenFull() {
        LoginRateLimiter limiter = new LoginRateLimiter(new SimpleMeterRegistry());
        limiter.configureForTest(2, Duration.ofMinutes(1), Duration.ofMinutes(1), 1,
                System::currentTimeMillis);
        limiter.recordFailure("10.0.0.1");
        limiter.recordFailure("10.0.0.2");
        assertTrue(limiter.isBlocked("10.0.0.3"));
    }
}
