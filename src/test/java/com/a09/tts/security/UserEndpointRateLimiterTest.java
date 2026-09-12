package com.a09.tts.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserEndpointRateLimiterTest {
    @Test
    void limitsEachUserAndEndpointIndependentlyAndResetsWindow() {
        AtomicLong now = new AtomicLong(1_000);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UserEndpointRateLimiter limiter = new UserEndpointRateLimiter(registry);
        limiter.configureForTest(2, Duration.ofSeconds(10), 100, now::get);

        assertTrue(limiter.check("alice", "GET:voices").allowed());
        assertTrue(limiter.check("alice", "GET:voices").allowed());
        assertFalse(limiter.check("alice", "GET:voices").allowed());
        assertTrue(limiter.check("alice", "GET:tasks").allowed());
        assertTrue(limiter.check("bob", "GET:voices").allowed());
        assertTrue(registry.get("fctts.rate.limit.rejected")
                .tag("scope", "user_endpoint").counter().count() >= 1);

        now.addAndGet(10_001);
        assertTrue(limiter.check("alice", "GET:voices").allowed());
    }

    @Test
    void boundedFallbackRejectsNewBucketsWhenFull() {
        UserEndpointRateLimiter limiter = new UserEndpointRateLimiter(new SimpleMeterRegistry());
        limiter.configureForTest(10, Duration.ofMinutes(1), 1, System::currentTimeMillis);
        assertTrue(limiter.check("alice", "GET:voices").allowed());
        assertFalse(limiter.check("bob", "GET:voices").allowed());
    }
}
