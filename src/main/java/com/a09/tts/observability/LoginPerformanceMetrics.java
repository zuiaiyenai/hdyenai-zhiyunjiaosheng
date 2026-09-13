package com.a09.tts.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class LoginPerformanceMetrics {
    public static final String TOTAL = "total";
    public static final String RATE_LIMIT = "rate_limit";
    public static final String REDIS = "redis";
    public static final String DATABASE = "database";
    public static final String BCRYPT = "bcrypt";
    public static final String JWT = "jwt";

    private final MeterRegistry meterRegistry;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public LoginPerformanceMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public <T> T record(String stage, Supplier<T> operation) {
        long started = System.nanoTime();
        try {
            return operation.get();
        } finally {
            timer(stage).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private Timer timer(String stage) {
        return timers.computeIfAbsent(stage, key -> Timer.builder("fctts.login.stage")
                .description("Login request latency split by fixed processing stage")
                .tag("stage", key)
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry));
    }
}
