package com.a09.tts.task;

import com.a09.tts.api.PageResult;
import com.a09.tts.api.ResourceNotFoundException;
import com.a09.tts.task.AsyncTaskService.TaskCapacityException;
import com.a09.tts.task.AsyncTaskService.TaskSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskServiceTest {

    @Test
    void executesPersistedPayloadWithoutSubmissionCallback() throws Exception {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        Instant now = Instant.now();
        TaskRecord persisted = task("persisted", "alice", "RESTORED",
                "{\"value\":\"from-db\"}", 1, now);
        assertEquals(TaskRepository.CreateDisposition.CREATED,
                repository.create(persisted, 2).disposition());
        TaskDispatcher dispatcher = dispatcher(task ->
                new ObjectMapper().readTree(task.payload()).path("value").asText());
        AsyncTaskService service = service(repository, dispatcher, 1,
                Duration.ofSeconds(2), Duration.ofMillis(10), 2,
                new SimpleMeterRegistry());
        try {
            TaskRecord completed = awaitTerminal(service, "persisted", "alice");
            assertEquals(TaskStatus.SUCCESS, completed.status());
            assertEquals("from-db", completed.resultData());
            assertEquals(1, completed.attempts());
            assertTrue(completed.workerId() == null);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void retriesWithBackoffThenSucceeds() throws Exception {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        AtomicInteger executions = new AtomicInteger();
        TaskDispatcher dispatcher = dispatcher(task -> {
            if (executions.incrementAndGet() < 3) {
                throw new IllegalStateException("retry");
            }
            return "done";
        });
        AsyncTaskService service = service(repository, dispatcher, 1,
                Duration.ofSeconds(2), Duration.ofMillis(20), 2,
                new SimpleMeterRegistry());
        try {
            TaskSubmission submission = service.submit(
                    "alice", "RETRY", null, Map.of("id", 1), 3);
            TaskRecord completed = awaitTerminal(service, submission.taskId(), "alice");
            assertEquals(TaskStatus.SUCCESS, completed.status());
            assertEquals(3, completed.attempts());
            assertEquals(3, executions.get());
            assertTrue(completed.version() >= 6);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void recoversStaleClaimAndFailsExhaustedClaim() throws Exception {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        Instant now = Instant.now();
        repository.create(task("retry-stale", "alice", "STALE", "{}", 2, now), 2);
        repository.claimNext("dead-worker-1", now).orElseThrow();
        repository.updateHeartbeat("retry-stale", "dead-worker-1", now.minusSeconds(5));
        repository.create(task("failed-stale", "bob", "STALE", "{}", 1, now), 2);
        repository.claimNext("dead-worker-2", now).orElseThrow();
        repository.updateHeartbeat("failed-stale", "dead-worker-2", now.minusSeconds(5));
        AtomicInteger cleaned = new AtomicInteger();
        TaskDispatcher dispatcher = new TaskDispatcher() {
            @Override
            public String execute(TaskRecord task) {
                return "recovered";
            }

            @Override
            public void cleanup(TaskRecord task) {
                if (task.status() == TaskStatus.FAILED) {
                    cleaned.incrementAndGet();
                }
            }

            @Override
            public void cleanupUncommittedResult(TaskRecord task, String resultData) {
            }
        };
        AsyncTaskService service = service(repository, dispatcher, 1,
                Duration.ofSeconds(2), Duration.ofMillis(10), 2,
                new SimpleMeterRegistry());
        try {
            TaskRecord recovered = awaitTerminal(service, "retry-stale", "alice");
            TaskRecord failed = awaitTerminal(service, "failed-stale", "bob");
            assertEquals(TaskStatus.SUCCESS, recovered.status());
            assertEquals(2, recovered.attempts());
            assertEquals(TaskStatus.FAILED, failed.status());
            assertEquals("STALE_ATTEMPTS_EXHAUSTED", failed.errorCode());
            assertEquals(1, cleaned.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void claimsEachTaskOnceAcrossMultipleWorkerInstances() throws Exception {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();
        TaskDispatcher dispatcher = dispatcher(task -> {
            executions.computeIfAbsent(task.id(), ignored -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(5);
            return task.id();
        });
        AsyncTaskService first = service(repository, dispatcher, 2,
                Duration.ofSeconds(2), Duration.ofMillis(10), 30,
                new SimpleMeterRegistry());
        AsyncTaskService second = service(repository, dispatcher, 2,
                Duration.ofSeconds(2), Duration.ofMillis(10), 30,
                new SimpleMeterRegistry());
        try {
            List<TaskSubmission> submitted = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                submitted.add(first.submit("user-" + index, "MULTI", null,
                        Map.of("index", index), 1));
            }
            for (int index = 0; index < submitted.size(); index++) {
                awaitTerminal(first, submitted.get(index).taskId(), "user-" + index);
            }
            assertEquals(20, executions.size());
            assertTrue(executions.values().stream().allMatch(value -> value.get() == 1));
        } finally {
            first.shutdown();
            second.shutdown();
        }
    }

    @Test
    void enforcesTimeoutCancellationDeduplicationAndUserCapacity() throws Exception {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerRelease = new CountDownLatch(1);
        AtomicInteger cleanupCalls = new AtomicInteger();
        TaskDispatcher dispatcher = new TaskDispatcher() {
            @Override
            public String execute(TaskRecord task) throws Exception {
                if (task.type().equals("TIMEOUT")) {
                    Thread.sleep(10_000);
                }
                blockerStarted.countDown();
                blockerRelease.await();
                return "done";
            }

            @Override
            public void cleanup(TaskRecord task) {
                cleanupCalls.incrementAndGet();
            }

            @Override
            public void cleanupUncommittedResult(TaskRecord task, String resultData) {
            }
        };
        AsyncTaskService timeoutService = service(repository, dispatcher, 1,
                Duration.ofMillis(100), Duration.ofMillis(10), 1,
                new SimpleMeterRegistry());
        try {
            TaskSubmission timed = timeoutService.submit(
                    "timeout-user", "TIMEOUT", null, Map.of("id", 1), 1);
            assertEquals(TaskStatus.TIMEOUT,
                    awaitTerminal(timeoutService, timed.taskId(), "timeout-user").status());

            TaskSubmission blocker = timeoutService.submit(
                    "alice", "BLOCK", "same", Map.of("id", 2), 1);
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
            TaskSubmission duplicate = timeoutService.submit(
                    "alice", "BLOCK", "same", Map.of("id", 2), 1);
            assertTrue(duplicate.duplicate());
            assertEquals(blocker.taskId(), duplicate.taskId());
            assertThrows(TaskCapacityException.class, () -> timeoutService.submit(
                    "alice", "OTHER", null, Map.of("id", 3), 1));

            TaskSubmission queued = timeoutService.submit(
                    "bob", "QUEUED", null, Map.of("id", 4), 1);
            assertEquals(TaskStatus.CANCELLED,
                    timeoutService.cancel(queued.taskId(), "bob").status());
            assertThrows(ResourceNotFoundException.class,
                    () -> timeoutService.cancel(blocker.taskId(), "bob"));
            timeoutService.cancel(blocker.taskId(), "alice");
            blockerRelease.countDown();
            assertTrue(awaitValue(cleanupCalls, 3));
        } finally {
            blockerRelease.countDown();
            timeoutService.shutdown();
        }
    }

    @Test
    void gracefulShutdownRequeuesInterruptedWork() throws Exception {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        CountDownLatch started = new CountDownLatch(1);
        TaskDispatcher dispatcher = dispatcher(task -> {
            started.countDown();
            Thread.sleep(10_000);
            return "late";
        });
        AsyncTaskService service = service(repository, dispatcher, 1,
                Duration.ofSeconds(20), Duration.ofMillis(10), 2,
                new SimpleMeterRegistry());
        TaskSubmission submitted = service.submit(
                "alice", "SHUTDOWN", null, Map.of("id", 1), 2);
        assertTrue(started.await(1, TimeUnit.SECONDS));

        service.shutdown();

        TaskRecord task = repository.findById(submitted.taskId()).orElseThrow();
        assertEquals(TaskStatus.PENDING, task.status());
        assertEquals("WORKER_SHUTDOWN", task.errorCode());
        assertTrue(task.workerId() == null);
    }

    @Test
    void releasesUnstartedClaimWithoutConsumingAnAttempt() {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        Instant now = Instant.now();
        repository.create(task("unstarted", "alice", "SHUTDOWN", "{}", 1, now), 1);
        TaskRecord claimed = repository.claimNext("stopping-worker", now).orElseThrow();
        assertEquals(1, claimed.attempts());

        assertTrue(repository.releaseClaim(claimed.id(), claimed.workerId(),
                "WORKER_SHUTDOWN", "worker 关闭前释放任务", now));

        TaskRecord released = repository.findById(claimed.id()).orElseThrow();
        assertEquals(TaskStatus.PENDING, released.status());
        assertEquals(0, released.attempts());
        assertTrue(released.workerId() == null);
    }

    @Test
    void resourceSaturationDefersWithoutConsumingAnAttempt() throws Exception {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        TaskResourceBulkheads bulkheads = new TaskResourceBulkheads(
                Map.of(TaskResource.TTS, 1, TaskResource.ASR, 1,
                        TaskResource.FFMPEG, 1, TaskResource.COURSEWARE, 1),
                Duration.ZERO, new SimpleMeterRegistry());
        TaskDispatcher dispatcher = dispatcher(task -> {
            try (TaskResourceBulkheads.Permit ignored =
                         bulkheads.acquire(TaskResource.TTS)) {
                return "done";
            }
        });
        AsyncTaskService service = service(repository, dispatcher, 1,
                Duration.ofSeconds(2), Duration.ofMillis(10), 1,
                new SimpleMeterRegistry());
        try {
            TaskSubmission submission;
            try (TaskResourceBulkheads.Permit ignored = bulkheads.acquire(TaskResource.TTS)) {
                submission = service.submit(
                        "alice", "SOUND_CLONE", null, Map.of("id", 1), 1);
                TaskRecord deferred = awaitStatus(
                        service, submission.taskId(), "alice", TaskStatus.PENDING);
                assertEquals(0, deferred.attempts());
                assertEquals("RESOURCE_SATURATED", deferred.errorCode());
            }
            TaskRecord completed = awaitTerminal(service, submission.taskId(), "alice");
            assertEquals(TaskStatus.SUCCESS, completed.status());
            assertEquals(1, completed.attempts());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shutdownWhileWaitingForResourceReleasesClaimWithoutAttempt() throws Exception {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        TaskResourceBulkheads bulkheads = new TaskResourceBulkheads(
                Map.of(TaskResource.TTS, 1, TaskResource.ASR, 1,
                        TaskResource.FFMPEG, 1, TaskResource.COURSEWARE, 1),
                Duration.ofSeconds(30), new SimpleMeterRegistry());
        AtomicInteger entered = new AtomicInteger();
        TaskDispatcher dispatcher = dispatcher(task -> {
            entered.incrementAndGet();
            try (TaskResourceBulkheads.Permit ignored =
                         bulkheads.acquire(TaskResource.TTS)) {
                return "done";
            }
        });
        AsyncTaskService service = service(repository, dispatcher, 1,
                Duration.ofSeconds(30), Duration.ofMillis(10), 1,
                new SimpleMeterRegistry());
        TaskSubmission submission;
        try (TaskResourceBulkheads.Permit ignored = bulkheads.acquire(TaskResource.TTS)) {
            submission = service.submit(
                    "alice", "SOUND_CLONE", null, Map.of("id", 1), 1);
            assertTrue(awaitValue(entered, 1));
            service.shutdown();
        }

        TaskRecord released = service.get(submission.taskId(), "alice");
        assertEquals(TaskStatus.PENDING, released.status());
        assertEquals(0, released.attempts());
        assertEquals("WORKER_SHUTDOWN", released.errorCode());
    }

    @Test
    void paginatesTasksWithinOwnerBoundary() {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        Instant now = Instant.now();
        completeTask(repository, "alice-1", "alice", now.minusSeconds(2));
        completeTask(repository, "bob-1", "bob", now.minusSeconds(1));
        completeTask(repository, "alice-2", "alice", now);
        AsyncTaskService service = service(repository, dispatcher(task -> "unused"), 1,
                Duration.ofSeconds(1), Duration.ofMillis(10), 2,
                new SimpleMeterRegistry());
        try {
            PageResult<TaskRecord> first = service.list("alice", 0, 1);
            PageResult<TaskRecord> second = service.list("alice", 1, 1);
            assertEquals(1, first.content().size());
            assertTrue(first.hasNext());
            assertEquals(1, second.content().size());
            assertTrue(!second.hasNext());
            assertTrue(first.content().stream().noneMatch(task -> task.owner().equals("bob")));
            assertThrows(IllegalArgumentException.class, () -> service.list("alice", 0, 101));
        } finally {
            service.shutdown();
        }
    }

    private AsyncTaskService service(
            InMemoryTaskRepository repository, TaskDispatcher dispatcher,
            int workers, Duration timeout, Duration retryBase,
            int perUser, SimpleMeterRegistry registry) {
        return new AsyncTaskService(repository, dispatcher, new ObjectMapper(), workers,
                Duration.ofMillis(5), timeout, Duration.ofMillis(20),
                Duration.ofMillis(100), Duration.ofMillis(20), retryBase,
                Duration.ofMillis(100), Duration.ofSeconds(1), perUser, registry);
    }

    private TaskDispatcher dispatcher(CheckedExecution execution) {
        return new TaskDispatcher() {
            @Override
            public String execute(TaskRecord task) throws Exception {
                return execution.execute(task);
            }

            @Override
            public void cleanup(TaskRecord task) {
            }

            @Override
            public void cleanupUncommittedResult(TaskRecord task, String resultData) {
            }
        };
    }

    private TaskRecord task(String id, String owner, String type,
                            String payload, int maxAttempts, Instant createdAt) {
        return new TaskRecord(id, owner, type, TaskStatus.PENDING, 0,
                payload, null, null, null, null, 0, maxAttempts,
                createdAt, createdAt, null, null, null, null, 0);
    }

    private void completeTask(InMemoryTaskRepository repository, String id,
                              String owner, Instant createdAt) {
        TaskRecord task = task(id, owner, "TEST", "{}", 1, createdAt);
        assertEquals(TaskRepository.CreateDisposition.CREATED,
                repository.create(task, 2).disposition());
        assertTrue(repository.markRunning(id, createdAt));
        assertTrue(repository.markSucceeded(id, "done", createdAt));
    }

    private TaskRecord awaitTerminal(AsyncTaskService service, String id, String owner)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        TaskRecord task;
        do {
            task = service.get(id, owner);
            if (task.status().terminal()) {
                return task;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("task did not become terminal: " + task);
    }

    private TaskRecord awaitStatus(
            AsyncTaskService service, String id, String owner, TaskStatus status)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        TaskRecord task;
        do {
            task = service.get(id, owner);
            if (task.status() == status && task.errorCode() != null) {
                return task;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("task did not reach status: " + task);
    }

    private boolean awaitValue(AtomicInteger value, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (value.get() >= expected) {
                return true;
            }
            Thread.sleep(5);
        }
        return value.get() >= expected;
    }

    @FunctionalInterface
    private interface CheckedExecution {
        String execute(TaskRecord task) throws Exception;
    }
}
