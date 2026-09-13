package com.a09.tts.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "app.storage.provider=aliyun-oss")
@ActiveProfiles("nodb")
@EnabledIfEnvironmentVariable(named = "ALIYUN_OSS_CAPACITY_TEST", matches = "true")
class AliyunOssCapacityIntegrationTest {
    private static final long MIB = 1024L * 1024L;
    private static final int BUFFER_BYTES = 1024 * 1024;
    private static final DateTimeFormatter RUN_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    @Autowired
    private ObjectStorageService storage;

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.bucket-name}")
    private String bucket;

    @Test
    void verifiesStreamingThroughputConcurrencyCleanupAndFailures() throws Exception {
        String runId = UUID.randomUUID().toString();
        String prefix = "verification/phase16-oss/" + runId + "/";
        Path runRoot = Path.of("target", "phase16-oss-" + RUN_TIME.format(Instant.now()))
                .toAbsolutePath().normalize();
        Files.createDirectories(runRoot);
        Path evidencePath = runRoot.resolve("phase16-oss-raw-evidence.json");
        List<String> trackedKeys = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Map<String, Object>> serialCases = new ArrayList<>();
        Map<String, Object> concurrent = new LinkedHashMap<>();
        Map<String, Object> failureBehavior = new LinkedHashMap<>();
        Map<String, Object> cleanup = new LinkedHashMap<>();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("phase", "16.6");
        evidence.put("started_at", Instant.now().toString());
        evidence.put("scope", "real Alibaba Cloud OSS through the application's streaming ObjectStorageService");
        evidence.put("credentials_recorded", false);
        evidence.put("storage_location_recorded", false);
        evidence.put("protocol", Map.of(
                "serial_sizes_mib", List.of(10, 100, 500),
                "stream_buffer_bytes", BUFFER_BYTES,
                "concurrent_uploads", 4,
                "concurrent_object_size_mib", 10,
                "object_prefix_is_unique", true));
        evidence.put("serial_cases", serialCases);
        evidence.put("concurrent_upload", concurrent);
        evidence.put("failure_behavior", failureBehavior);
        evidence.put("cleanup", cleanup);

        try {
            for (int sizeMiB : List.of(10, 100, 500)) {
                serialCases.add(runSerialCase(runRoot, prefix, sizeMiB, trackedKeys, failures));
            }
            concurrent.putAll(runConcurrentUploads(
                    runRoot, prefix, trackedKeys, failures));
            failureBehavior.putAll(runFailureBehavior(prefix, trackedKeys, failures));
        } catch (Exception exception) {
            failures.add("unhandled:" + exception.getClass().getSimpleName());
        } finally {
            int deleteFailures = 0;
            for (String key : trackedKeys) {
                try {
                    if (storage.exists(key)) {
                        storage.delete(key);
                    }
                } catch (Exception exception) {
                    deleteFailures++;
                    failures.add("cleanup-delete:" + exception.getClass().getSimpleName());
                }
            }
            int remaining = 0;
            for (String key : trackedKeys) {
                try {
                    if (storage.exists(key)) {
                        remaining++;
                    }
                } catch (Exception exception) {
                    remaining++;
                    failures.add("cleanup-verify:" + exception.getClass().getSimpleName());
                }
            }
            cleanup.put("tracked_objects", trackedKeys.size());
            cleanup.put("delete_failures", deleteFailures);
            cleanup.put("remaining_objects", remaining);
            cleanup.put("all_test_objects_removed", remaining == 0 && deleteFailures == 0);
            if (remaining != 0) {
                failures.add("cleanup-remaining-objects:" + remaining);
            }
            evidence.put("finished_at", Instant.now().toString());
            evidence.put("status", failures.isEmpty() ? "VERIFIED" : "FAILED");
            evidence.put("failures", failures);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(evidencePath.toFile(), evidence);
            System.out.println("PHASE16_OSS_EVIDENCE=" + evidencePath);
            System.out.println("PHASE16_OSS_EVIDENCE_SHA256=" + sha256(evidencePath));
        }

        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    private Map<String, Object> runSerialCase(
            Path runRoot, String prefix, int sizeMiB,
            List<String> trackedKeys, List<String> failures) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("size_mib", sizeMiB);
        long bytes = sizeMiB * MIB;
        result.put("bytes", bytes);
        String key = prefix + "serial-" + sizeMiB + "m.bin";
        trackedKeys.add(key);
        Path source = runRoot.resolve("serial-" + sizeMiB + "m.bin");
        try {
            createSparseFile(source, bytes);
            String sourceSha256 = sha256(source);
            result.put("source_sha256", sourceSha256);

            Measurement upload;
            try (InputStream input = Files.newInputStream(source)) {
                upload = measure(() -> storage.store(
                        key, input, bytes, "application/octet-stream", sourceSha256));
            }
            upload.values.put("throughput_mib_per_second", sizeMiB / upload.wallSeconds());
            result.put("upload", upload.values);
            result.put("exists_after_upload", storage.exists(key));

            MessageDigest downloadedDigest = MessageDigest.getInstance("SHA-256");
            AtomicLong downloadedBytes = new AtomicLong();
            Measurement download = measure(() -> {
                try (InputStream input = storage.open(key)) {
                    byte[] buffer = new byte[BUFFER_BYTES];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        downloadedDigest.update(buffer, 0, read);
                        downloadedBytes.addAndGet(read);
                    }
                }
            });
            download.values.put("throughput_mib_per_second", sizeMiB / download.wallSeconds());
            result.put("download", download.values);
            result.put("downloaded_bytes", downloadedBytes.get());
            result.put("download_sha256", HexFormat.of().formatHex(downloadedDigest.digest()));
            result.put("integrity_verified", bytes == downloadedBytes.get()
                    && sourceSha256.equals(result.get("download_sha256")));

            long deleteStarted = System.nanoTime();
            storage.delete(key);
            result.put("delete_seconds", elapsedSeconds(deleteStarted));
            result.put("exists_after_delete", storage.exists(key));
            result.put("status", Boolean.TRUE.equals(result.get("integrity_verified"))
                    && Boolean.FALSE.equals(result.get("exists_after_delete"))
                    ? "VERIFIED" : "FAILED");
            if (!"VERIFIED".equals(result.get("status"))) {
                failures.add("serial-" + sizeMiB + "m-verification");
            }
        } catch (Exception exception) {
            result.put("status", "FAILED");
            result.put("error_type", exception.getClass().getSimpleName());
            failures.add("serial-" + sizeMiB + "m:" + exception.getClass().getSimpleName());
        } finally {
            try {
                Files.deleteIfExists(source);
            } catch (IOException exception) {
                failures.add("source-cleanup-" + sizeMiB + "m:" + exception.getClass().getSimpleName());
            }
        }
        return result;
    }

    private Map<String, Object> runConcurrentUploads(
            Path runRoot, String prefix, List<String> trackedKeys, List<String> failures) {
        Map<String, Object> result = new LinkedHashMap<>();
        int concurrency = 4;
        int sizeMiB = 10;
        long bytes = sizeMiB * MIB;
        Path source = runRoot.resolve("concurrent-10m.bin");
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            createSparseFile(source, bytes);
            String checksum = sha256(source);
            CountDownLatch ready = new CountDownLatch(concurrency);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Double>> futures = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                String key = prefix + "concurrent-" + index + ".bin";
                keys.add(key);
                trackedKeys.add(key);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("concurrent start timeout");
                    }
                    long started = System.nanoTime();
                    try (InputStream input = Files.newInputStream(source)) {
                        storage.store(key, input, bytes, "application/octet-stream", checksum);
                    }
                    return elapsedSeconds(started);
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IOException("concurrent workers were not ready");
            }
            List<Double> latencies = new ArrayList<>();
            Measurement measurement = measure(() -> {
                start.countDown();
                for (Future<Double> future : futures) {
                    latencies.add(future.get(5, TimeUnit.MINUTES));
                }
            });
            int verified = 0;
            for (String key : keys) {
                if (storage.exists(key) && checksum.equals(streamSha256(storage.open(key)))) {
                    verified++;
                }
            }
            result.put("concurrency", concurrency);
            result.put("object_size_mib", sizeMiB);
            result.put("objects", concurrency);
            result.put("successful_uploads", latencies.size());
            result.put("integrity_verified_objects", verified);
            result.put("wall_seconds", measurement.wallSeconds());
            result.put("aggregate_throughput_mib_per_second",
                    concurrency * sizeMiB / measurement.wallSeconds());
            result.put("individual_latency_seconds", latencies);
            result.put("resources", measurement.values);
            result.put("status", verified == concurrency ? "VERIFIED" : "FAILED");
            if (verified != concurrency) {
                failures.add("concurrent-integrity:" + verified + "/" + concurrency);
            }
        } catch (Exception exception) {
            result.put("status", "FAILED");
            result.put("error_type", exception.getClass().getSimpleName());
            failures.add("concurrent-upload:" + exception.getClass().getSimpleName());
        } finally {
            executor.shutdownNow();
            try {
                Files.deleteIfExists(source);
            } catch (IOException exception) {
                failures.add("concurrent-source-cleanup:" + exception.getClass().getSimpleName());
            }
        }
        return result;
    }

    private Map<String, Object> runFailureBehavior(
            String prefix, List<String> trackedKeys, List<String> failures) {
        Map<String, Object> result = new LinkedHashMap<>();
        String missingKey = prefix + "missing.bin";
        long missingStarted = System.nanoTime();
        try (InputStream ignored = storage.open(missingKey)) {
            result.put("missing_object", "FAILED_UNEXPECTED_SUCCESS");
            failures.add("missing-object-unexpected-success");
        } catch (FileNotFoundException expected) {
            result.put("missing_object", "BOUNDED_NOT_FOUND");
            result.put("missing_object_seconds", elapsedSeconds(missingStarted));
        } catch (Exception exception) {
            result.put("missing_object", "FAILED_WRONG_ERROR");
            result.put("missing_object_error_type", exception.getClass().getSimpleName());
            failures.add("missing-object:" + exception.getClass().getSimpleName());
        }

        String interruptedKey = prefix + "interrupted.bin";
        trackedKeys.add(interruptedKey);
        long interruptedStarted = System.nanoTime();
        try (InputStream input = new FailingInputStream(2 * MIB)) {
            storage.store(interruptedKey, input, 10 * MIB,
                    "application/octet-stream", null);
            result.put("interrupted_upload", "FAILED_UNEXPECTED_SUCCESS");
            failures.add("interrupted-upload-unexpected-success");
        } catch (IOException expected) {
            try {
                boolean exists = storage.exists(interruptedKey);
                result.put("interrupted_upload", exists
                        ? "FAILED_PARTIAL_OBJECT_VISIBLE" : "BOUNDED_NO_OBJECT");
                result.put("interrupted_upload_seconds", elapsedSeconds(interruptedStarted));
                result.put("interrupted_object_exists", exists);
                if (exists) {
                    failures.add("interrupted-upload-left-object");
                }
            } catch (Exception exception) {
                failures.add("interrupted-upload-verify:" + exception.getClass().getSimpleName());
            }
        }

        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setConnectionTimeout(5_000);
        configuration.setSocketTimeout(30_000);
        configuration.setRequestTimeout(60_000);
        configuration.setRequestTimeoutEnabled(true);
        OSS invalidClient = new OSSClientBuilder().build(
                endpoint.startsWith("http") ? endpoint : "https://" + endpoint,
                "invalid-access-key", "invalid-secret", configuration);
        AliyunOssObjectStorageService invalidStorage =
                new AliyunOssObjectStorageService(invalidClient, bucket);
        long unauthorizedStarted = System.nanoTime();
        try {
            invalidStorage.exists(prefix + "unauthorized.bin");
            result.put("invalid_credentials", "FAILED_UNEXPECTED_SUCCESS");
            failures.add("invalid-credentials-unexpected-success");
        } catch (IOException expected) {
            result.put("invalid_credentials", "BOUNDED_REJECTION");
            result.put("invalid_credentials_seconds", elapsedSeconds(unauthorizedStarted));
        } finally {
            invalidStorage.shutdown();
        }
        result.put("status", failures.stream().noneMatch(value ->
                value.startsWith("missing-object")
                        || value.startsWith("interrupted-upload")
                        || value.startsWith("invalid-credentials")) ? "VERIFIED" : "FAILED");
        return result;
    }

    private Measurement measure(CheckedRunnable operation) throws Exception {
        long heapBefore = heapUsed();
        AtomicLong heapPeak = new AtomicLong(heapBefore);
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                heapPeak.accumulateAndGet(heapUsed(), Math::max);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "phase16-oss-heap-sampler");
        sampler.setDaemon(true);
        long gcCountBefore = gcCollectionCount();
        long gcTimeBefore = gcCollectionTime();
        long cpuBefore = processCpuNanos();
        long started = System.nanoTime();
        sampler.start();
        try {
            operation.run();
        } finally {
            sampling.set(false);
            sampler.join(2_000);
        }
        double wallSeconds = elapsedSeconds(started);
        long heapAfter = heapUsed();
        heapPeak.accumulateAndGet(heapAfter, Math::max);
        long cpuNanos = Math.max(0, processCpuNanos() - cpuBefore);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("wall_seconds", wallSeconds);
        values.put("heap_before_mib", heapBefore / (double) MIB);
        values.put("heap_peak_mib", heapPeak.get() / (double) MIB);
        values.put("heap_after_mib", heapAfter / (double) MIB);
        values.put("heap_peak_delta_mib", (heapPeak.get() - heapBefore) / (double) MIB);
        values.put("gc_collection_count_delta", gcCollectionCount() - gcCountBefore);
        values.put("gc_collection_time_milliseconds_delta", gcCollectionTime() - gcTimeBefore);
        values.put("process_cpu_seconds", cpuNanos / 1_000_000_000.0);
        values.put("process_cpu_average_percent", wallSeconds == 0
                ? 0 : cpuNanos / 1_000_000_000.0 / wallSeconds * 100.0);
        return new Measurement(values, wallSeconds);
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long gcCollectionCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .filter(value -> value >= 0).sum();
    }

    private static long gcCollectionTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(value -> value >= 0).sum();
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
            return operatingSystem.getProcessCpuTime();
        }
        return 0;
    }

    private static void createSparseFile(Path path, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }

    private static String sha256(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return streamSha256(input);
        }
    }

    private static String streamSha256(InputStream input) throws Exception {
        try (InputStream closeable = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = closeable.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static double elapsedSeconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private record Measurement(Map<String, Object> values, double wallSeconds) { }

    private static final class FailingInputStream extends InputStream {
        private long remaining;

        private FailingInputStream(long bytesBeforeFailure) {
            this.remaining = bytesBeforeFailure;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new IOException("intentional interrupted upload");
            }
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                throw new IOException("intentional interrupted upload");
            }
            int count = (int) Math.min(remaining, length);
            Arrays.fill(buffer, offset, offset + count, (byte) 0);
            remaining -= count;
            return count;
        }
    }
}
