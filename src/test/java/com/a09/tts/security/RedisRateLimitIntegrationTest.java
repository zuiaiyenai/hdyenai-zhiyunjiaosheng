package com.a09.tts.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "app.redis.enabled=true",
        "app.security.login-rate-limit.max-failures=3",
        "app.security.login-rate-limit.window=5s",
        "app.security.login-rate-limit.lock-duration=5s",
        "app.security.endpoint-rate-limit.max-requests=10",
        "app.security.endpoint-rate-limit.window=5s"
})
@ActiveProfiles("nodb")
@EnabledIfSystemProperty(named = "phase8.redis.enabled", matches = "true")
class RedisRateLimitIntegrationTest {
    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @Autowired
    private UserEndpointRateLimiter endpointRateLimiter;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void luaLimitsConcurrentRequestsAtomicallyAndExportsCacheMetrics() throws Exception {
        try (var connection = connectionFactory.getConnection()) {
            assertNotNull(connection.ping());
        }
        String unique = UUID.randomUUID().toString();

        var executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int index = 0; index < 40; index++) {
                calls.add(() -> endpointRateLimiter.check(unique, "GET:phase8").allowed());
            }
            long allowed = executor.invokeAll(calls).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .count();
            assertEquals(10, allowed);
        } finally {
            executor.shutdownNow();
        }

        for (int index = 0; index < 3; index++) {
            loginRateLimiter.recordFailure(unique);
        }
        assertTrue(loginRateLimiter.isBlocked(unique));

        Cache cache = cacheManager.getCache("voiceById");
        assertNotNull(cache);
        String cacheKey = "phase8:" + unique;
        cache.put(cacheKey, "value");
        assertEquals("value", cache.get(cacheKey, String.class));
        assertNull(cache.get(cacheKey + ":missing"));
        assertTrue(meterRegistry.get("cache.gets").tag("cache", "voiceById")
                .tag("result", "hit").functionCounter().count() >= 1);
        assertTrue(meterRegistry.get("cache.gets").tag("cache", "voiceById")
                .tag("result", "miss").functionCounter().count() >= 1);
        cache.evict(cacheKey);
    }
}
