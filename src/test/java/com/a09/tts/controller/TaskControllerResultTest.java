package com.a09.tts.controller;

import com.a09.tts.api.ResourceNotFoundException;
import com.a09.tts.storage.InMemoryStoredObjectMetadataRepository;
import com.a09.tts.storage.LocalObjectStorageService;
import com.a09.tts.storage.ManagedObjectStorageService;
import com.a09.tts.storage.ObjectStorageKeys;
import com.a09.tts.task.AsyncTaskService;
import com.a09.tts.task.InMemoryTaskRepository;
import com.a09.tts.task.TaskRecord;
import com.a09.tts.task.TaskRepository;
import com.a09.tts.task.TaskRepository.CreateDisposition;
import com.a09.tts.task.TaskStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskControllerResultTest {
    @TempDir
    Path root;

    @Test
    void requiresBothTaskAndObjectOwnershipForResultDownload() throws Exception {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        AsyncTaskService tasks = new AsyncTaskService(
                repository, 1, 1, 2, Duration.ofSeconds(5), 2,
                new SimpleMeterRegistry());
        ManagedObjectStorageService storage = new ManagedObjectStorageService(
                new LocalObjectStorageService(root.toString()),
                new InMemoryStoredObjectMetadataRepository());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("username", "alice");
        TaskController controller = new TaskController(tasks, storage, request);
        byte[] expected = "owner result".getBytes(StandardCharsets.UTF_8);
        String aliceKey = ObjectStorageKeys.taskArtifact("alice", "upload", "result.json");
        storage.storeBytes("alice", aliceKey, expected, "application/json");
        complete(repository, "task", "alice", aliceKey);

        try (var input = controller.result("task").getBody().getInputStream()) {
            assertArrayEquals(expected, input.readAllBytes());
        }

        request.setAttribute("username", "bob");
        assertThrows(ResourceNotFoundException.class, () -> controller.result("task"));

        String bobKey = ObjectStorageKeys.taskArtifact("bob", "upload", "result.json");
        storage.storeBytes("bob", bobKey, expected, "application/json");
        complete(repository, "cross-owner", "alice", bobKey);
        request.setAttribute("username", "alice");
        assertThrows(ResourceNotFoundException.class,
                () -> controller.result("cross-owner"));
        tasks.shutdown();
    }

    private void complete(TaskRepository repository, String id, String owner, String resultKey) {
        Instant now = Instant.now();
        TaskRecord task = new TaskRecord(id, owner, "ASR_TRANSCRIBE", TaskStatus.PENDING,
                0, null, null, null, now, null, null);
        assertEquals(CreateDisposition.CREATED, repository.create(task, 2).disposition());
        assertTrue(repository.markRunning(id, now));
        assertTrue(repository.markSucceeded(id, resultKey, now));
    }
}
