package com.a09.tts.task;

import com.a09.tts.api.VideoSubtitlePreview;
import com.a09.tts.service.ASRService;
import com.a09.tts.service.AccessibilityService;
import com.a09.tts.service.CoursewareProjectService;
import com.a09.tts.service.PPTService;
import com.a09.tts.service.SoundCloneService;
import com.a09.tts.service.SpeakingPracticeService;
import com.a09.tts.service.VideoVoiceSwapService;
import com.a09.tts.storage.InMemoryStoredObjectMetadataRepository;
import com.a09.tts.storage.LocalObjectStorageService;
import com.a09.tts.storage.ManagedObjectStorageService;
import com.a09.tts.storage.ObjectStorageKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "fctts.it.media-streaming", matches = "true")
class MediaStreamingMemoryIntegrationTest {
    private static final long MIB = 1024L * 1024L;
    private static final long MAX_PEAK_GROWTH = 64L * MIB;

    @TempDir
    Path root;

    @Test
    void streamsOneHundredAndFiveHundredMiBVideoResultsWithBoundedHeap() throws Exception {
        verifySize(100L * MIB);
        verifySize(500L * MIB);
    }

    private void verifySize(long size) throws Exception {
        ManagedObjectStorageService storage = new ManagedObjectStorageService(
                new LocalObjectStorageService(root.resolve("objects").toString()),
                new InMemoryStoredObjectMetadataRepository());
        SparseVideoService videoService = new SparseVideoService(size);
        ObjectMapper objectMapper = new ObjectMapper();
        TaskWorkDispatcher dispatcher = new TaskWorkDispatcher(
                storage, mock(ASRService.class), mock(AccessibilityService.class),
                videoService, mock(SoundCloneService.class), mock(SpeakingPracticeService.class),
                mock(PPTService.class), mock(CoursewareProjectService.class), objectMapper,
                TaskResourceBulkheads.unrestricted());
        String uploadId = "streaming-" + size;
        String inputKey = ObjectStorageKeys.taskInput("alice", uploadId, "input.mp4");
        storage.storeBytes("alice", inputKey, new byte[]{1}, "video/mp4");
        TaskPayloads.VideoSwap payload = new TaskPayloads.VideoSwap(
                inputKey, uploadId, "input.mp4", "longxiao",
                "用于跳过外部 ASR 的测试文本", null, false);
        Instant now = Instant.now();
        TaskRecord task = new TaskRecord(
                "task-" + size, "alice", "VIDEO_VOICE_SWAP", TaskStatus.RUNNING, 50,
                objectMapper.writeValueAsString(payload), null, null, null, null,
                1, 3, now, now, now, now, null, "worker-1", 1);

        forceGc();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long heapBefore = memory.getHeapMemoryUsage().getUsed();
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcTimeMillis();
        AtomicLong heapPeak = new AtomicLong(heapBefore);
        AtomicBoolean running = new AtomicBoolean(true);
        Thread sampler = startSampler(memory, heapPeak, running);

        String resultKey;
        try {
            resultKey = dispatcher.execute(task);
        } finally {
            running.set(false);
            sampler.join();
        }
        long heapAtCompletion = memory.getHeapMemoryUsage().getUsed();
        long gcCountDelta = gcCount() - gcCountBefore;
        long gcTimeDelta = gcTimeMillis() - gcTimeBefore;

        assertEquals(size, storage.requireMetadata("alice", resultKey).size());
        assertFalse(Files.exists(videoService.outputPath));
        storage.delete("alice", resultKey);
        storage.delete("alice", inputKey);
        forceGc();
        long heapAfter = memory.getHeapMemoryUsage().getUsed();
        long peakGrowth = Math.max(0, heapPeak.get() - heapBefore);
        assertTrue(peakGrowth < MAX_PEAK_GROWTH,
                () -> "heap growth was " + peakGrowth + " bytes for " + size + " bytes");

        System.out.printf(
                "PHASE16_MEDIA_STREAMING sizeBytes=%d maxHeapBytes=%d heapBeforeBytes=%d "
                        + "heapPeakBytes=%d heapAtCompletionBytes=%d heapAfterGcBytes=%d "
                        + "peakGrowthBytes=%d gcCountDelta=%d gcTimeDeltaMs=%d%n",
                size, Runtime.getRuntime().maxMemory(), heapBefore,
                heapPeak.get(), heapAtCompletion, heapAfter,
                peakGrowth, gcCountDelta, gcTimeDelta);
    }

    private Thread startSampler(
            MemoryMXBean memory, AtomicLong heapPeak, AtomicBoolean running) {
        Thread sampler = new Thread(() -> {
            while (running.get()) {
                heapPeak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            heapPeak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
        }, "phase16-heap-sampler");
        sampler.setDaemon(true);
        sampler.start();
        return sampler;
    }

    private void forceGc() throws InterruptedException {
        System.gc();
        Thread.sleep(200);
    }

    private long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .filter(value -> value >= 0)
                .sum();
    }

    private long gcTimeMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(value -> value >= 0)
                .sum();
    }

    private static final class SparseVideoService implements VideoVoiceSwapService {
        private final long size;
        private Path outputPath;

        private SparseVideoService(long size) {
            this.size = size;
        }

        @Override
        public void processVideo(String videoPath, String voiceType,
                                 double speed, double pitch, double rhythm,
                                 Path outputPath) throws Exception {
            processVideo(videoPath, voiceType, speed, pitch, rhythm,
                    null, null, true, outputPath);
        }

        @Override
        public void processVideo(String videoPath, String voiceType,
                                 double speed, double pitch, double rhythm,
                                 String transcript, String subtitles,
                                 boolean includeSubtitles, Path outputPath) throws Exception {
            this.outputPath = outputPath;
            try (FileChannel output = FileChannel.open(
                    outputPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                output.position(size - 1);
                output.write(ByteBuffer.wrap(new byte[]{1}));
            }
        }

        @Override
        public VideoSubtitlePreview generateSubtitlePreview(String videoPath) {
            throw new UnsupportedOperationException();
        }
    }
}
