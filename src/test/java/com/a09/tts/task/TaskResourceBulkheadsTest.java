package com.a09.tts.task;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResourceBulkheadsTest {

    @Test
    void isolatesResourcesRejectsOverflowAndAllowsReentrantCalls() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskResourceBulkheads bulkheads = new TaskResourceBulkheads(
                Map.of(TaskResource.TTS, 1, TaskResource.ASR, 1,
                        TaskResource.FFMPEG, 1, TaskResource.COURSEWARE, 1),
                Duration.ofMillis(20), registry);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> {
                try (TaskResourceBulkheads.Permit ignored = bulkheads.acquire(TaskResource.TTS);
                     TaskResourceBulkheads.Permit nested = bulkheads.acquire(TaskResource.TTS)) {
                    started.countDown();
                    release.await();
                }
                return null;
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));

            assertThrows(ResourceCapacityException.class,
                    () -> bulkheads.acquire(TaskResource.TTS));
            try (TaskResourceBulkheads.Permit ignored = bulkheads.acquire(TaskResource.ASR)) {
                assertEquals(1.0, registry.get("fctts.resource.bulkhead.active")
                        .tag("resource", "asr").gauge().value());
            }
            assertEquals(1.0, registry.get("fctts.resource.bulkhead.rejected")
                    .tag("resource", "tts").counter().count());

            release.countDown();
            holder.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void acquiresCompositeProfilesInStableOrder() {
        TaskResourceBulkheads bulkheads = new TaskResourceBulkheads(
                Map.of(TaskResource.TTS, 1, TaskResource.ASR, 1,
                        TaskResource.FFMPEG, 1, TaskResource.COURSEWARE, 1),
                Duration.ZERO, new SimpleMeterRegistry());

        try (TaskResourceBulkheads.Permit ignored = bulkheads.acquire(
                TaskResourceProfiles.resourcesFor("VIDEO_VOICE_SWAP"))) {
            try (TaskResourceBulkheads.Permit nested = bulkheads.acquire(
                    Set.of(TaskResource.TTS, TaskResource.ASR))) {
                // Same-thread nested service calls reuse the outer permits.
            }
        }

        assertThrows(IllegalArgumentException.class,
                () -> TaskResourceProfiles.resourcesFor("UNKNOWN"));
        assertEquals(Set.of(TaskResource.COURSEWARE, TaskResource.FFMPEG),
                TaskResourceProfiles.resourcesFor("COURSEWARE_VIDEO"));
    }
}
