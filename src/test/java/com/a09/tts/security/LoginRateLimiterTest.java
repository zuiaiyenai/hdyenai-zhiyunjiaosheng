package com.a09.tts.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {
    @Test
    void locksExpiresAndClearsAfterSuccess() {
        AtomicLong now = new AtomicLong(1_000);
        LoginRateLimiter limiter = new LoginRateLimiter();
        limiter.configureForTest(3, Duration.ofMinutes(1), Duration.ofMinutes(5), now::get);

        limiter.recordFailure("127.0.0.1", "alice");
        limiter.recordFailure("127.0.0.1", "alice");
        assertFalse(limiter.isBlocked("127.0.0.1", "alice"));
        limiter.recordSuccess("127.0.0.1", "alice");

        limiter.recordFailure("127.0.0.1", "alice");
        limiter.recordFailure("127.0.0.1", "alice");
        assertFalse(limiter.isBlocked("127.0.0.1", "alice"));
        limiter.recordFailure("127.0.0.1", "alice");
        assertTrue(limiter.isBlocked("127.0.0.1", "alice"));

        now.addAndGet(Duration.ofMinutes(5).toMillis() + 1);
        assertFalse(limiter.isBlocked("127.0.0.1", "alice"));
    }

    @Test
    void keyCombinesIpAndUsername() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        limiter.configureForTest(1, Duration.ofMinutes(1), Duration.ofMinutes(1), System::currentTimeMillis);
        limiter.recordFailure("10.0.0.1", "alice");
        assertTrue(limiter.isBlocked("10.0.0.1", "alice"));
        assertFalse(limiter.isBlocked("10.0.0.2", "alice"));
        assertFalse(limiter.isBlocked("10.0.0.1", "bob"));
    }
}
