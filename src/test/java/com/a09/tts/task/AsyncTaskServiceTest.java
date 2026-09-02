package com.a09.tts.task;

import com.a09.tts.api.PageResult;
import com.a09.tts.api.ResourceNotFoundException;
import com.a09.tts.task.AsyncTaskService.TaskCapacityException;
import com.a09.tts.task.AsyncTaskService.TaskSubmission;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskServiceTest {

    @Test
    void completesFailsAndScopesTasksByOwner() throws Exception {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        AsyncTaskService service = service(repository, 1, 1, 4, Duration.ofSeconds(2), 2);
        try {
            TaskSubmission success = service.submit("alice", "TEST", null, () -> "done");
            assertEquals(TaskStatus.SUCCESS, awaitTerminal(service, success.taskId(), "alice").status());
            assertEquals("done", service.get(success.taskId(), "alice").resultData());
            assertThrows(ResourceNotFoundException.class,
                    () -> service.get(success.taskId(), "bob"));
            assertThrows(ResourceNotFoundException.class,
                    () -> service.cancel(success.taskId(), "bob"));

            TaskSubmission failed = service.submit("alice", "TEST", null,
                    () -> {
                        throw new IllegalStateException("expected failure");
                    });
            TaskRecord failure = awaitTerminal(service, failed.taskId(), "alice");
            assertEquals(TaskStatus.FAILED, failure.status());
            assertEquals("任务执行失败", failure.errorMessage());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void timesOutAndCancelsTasks() throws Exception {
        AsyncTaskService timeoutService = service(
                new InMemoryTaskRepository(), 1, 1, 2, Duration.ofMillis(100), 2);
        try {
            TaskSubmission timed = timeoutService.submit("alice", "SLOW", null, () -> {
                Thread.sleep(10_000);
                return "late";
            });
            assertEquals(TaskStatus.TIMEOUT,
                    awaitTerminal(timeoutService, timed.taskId(), "alice").status());
        } finally {
            timeoutService.shutdown();
        }

        AsyncTaskService cancelService = service(
                new InMemoryTaskRepository(), 1, 1, 2, Duration.ofSeconds(5), 2);
        CountDownLatch started = new CountDownLatch(1);
        try {
            TaskSubmission submitted = cancelService.submit("alice", "CANCEL", null, () -> {
                started.countDown();
                Thread.sleep(10_000);
                return "late";
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(TaskStatus.CANCELLED,
                    cancelService.cancel(submitted.taskId(), "alice").status());
            assertEquals(TaskStatus.CANCELLED,
                    cancelService.get(submitted.taskId(), "alice").status());
        } finally {
            cancelService.shutdown();
        }
    }

    @Test
    void enforcesQueueUserLimitAndDuplicateProtection() throws Exception {
        AsyncTaskService perUser = service(
                new InMemoryTaskRepository(), 1, 1, 2, Duration.ofSeconds(5), 1);
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            TaskSubmission first = perUser.submit("alice", "MEDIA", "same", () -> {
                blocker.await();
                return "done";
            });
            TaskSubmission duplicate = perUser.submit("alice", "MEDIA", "same", () -> "duplicate");
            assertTrue(duplicate.duplicate());
            assertEquals(first.taskId(), duplicate.taskId());
            assertThrows(TaskCapacityException.class,
                    () -> perUser.submit("alice", "OTHER", null, () -> "other"));
            perUser.cancel(first.taskId(), "alice");
        } finally {
            blocker.countDown();
            perUser.shutdown();
        }

        AsyncTaskService queue = service(
                new InMemoryTaskRepository(), 1, 1, 1, Duration.ofSeconds(5), 3);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskSubmission first = queue.submit("alice", "ONE", null, () -> {
                running.countDown();
                release.await();
                return "one";
            });
            assertTrue(running.await(1, TimeUnit.SECONDS));
            TaskSubmission second = queue.submit("bob", "TWO", null, () -> "two");
            assertThrows(TaskCapacityException.class,
                    () -> queue.submit("carol", "THREE", null, () -> "three"));
            queue.cancel(first.taskId(), "alice");
            queue.cancel(second.taskId(), "bob");
        } finally {
            release.countDown();
            queue.shutdown();
        }
    }

    @Test
    void marksInterruptedTasksFailedOnStartup() {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        Instant now = Instant.now();
        repository.save(new TaskRecord("old", "alice", "VIDEO", TaskStatus.RUNNING,
                10, null, null, null, now, now, null));
        AsyncTaskService service = service(
                repository, 1, 1, 1, Duration.ofSeconds(1), 1);
        try {
            service.recoverInterruptedTasks();
            TaskRecord recovered = repository.findById("old").orElseThrow();
            assertEquals(TaskStatus.FAILED, recovered.status());
            assertTrue(recovered.errorMessage().contains("重启"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void paginatesTasksWithinOwnerBoundary() {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        Instant now = Instant.now();
        repository.save(task("alice-1", "alice", now.minusSeconds(2)));
        repository.save(task("bob-1", "bob", now.minusSeconds(1)));
        repository.save(task("alice-2", "alice", now));
        AsyncTaskService service = service(
                repository, 1, 1, 1, Duration.ofSeconds(1), 1);
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

    private TaskRecord task(String id, String owner, Instant createdAt) {
        return new TaskRecord(id, owner, "TEST", TaskStatus.SUCCESS,
                100, "done", null, null, createdAt, createdAt, createdAt);
    }

    private AsyncTaskService service(InMemoryTaskRepository repository,
                                     int core, int max, int queue,
                                     Duration timeout, int perUser) {
        return new AsyncTaskService(repository, core, max, queue, timeout, perUser);
    }

    private TaskRecord awaitTerminal(AsyncTaskService service, String id, String owner)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        TaskRecord task;
        do {
            task = service.get(id, owner);
            if (task.status().terminal()) {
                return task;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("task did not become terminal: " + task);
    }
}
