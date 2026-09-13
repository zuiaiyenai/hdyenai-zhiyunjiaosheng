package com.a09.tts.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginPerformanceMetricsTest {
    @Test
    void recordsSuccessfulAndFailedOperationsWithoutChangingTheirOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LoginPerformanceMetrics metrics = new LoginPerformanceMetrics(registry);

        assertEquals("ok", metrics.record(LoginPerformanceMetrics.DATABASE, () -> "ok"));
        assertThrows(IllegalStateException.class,
                () -> metrics.record(LoginPerformanceMetrics.DATABASE,
                        () -> { throw new IllegalStateException("failed"); }));

        assertEquals(2, registry.get("fctts.login.stage")
                .tag("stage", LoginPerformanceMetrics.DATABASE).timer().count());
    }
}
