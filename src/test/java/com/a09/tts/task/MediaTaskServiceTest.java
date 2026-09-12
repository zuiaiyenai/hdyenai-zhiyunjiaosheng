package com.a09.tts.task;

import com.a09.tts.TestMediaFiles;
import com.a09.tts.api.AsrResult;
import com.a09.tts.api.ResourceNotFoundException;
import com.a09.tts.security.UploadSecurityService;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaTaskServiceTest {
    @TempDir
    Path root;

    @Test
    void stagesInputRunsAsrStoresResultAndCleansInput() throws Exception {
        ManagedObjectStorageService storage = new ManagedObjectStorageService(
                new LocalObjectStorageService(root.toString()),
                new InMemoryStoredObjectMetadataRepository());
        ObjectMapper objectMapper = new ObjectMapper();
        ASRService asr = mock(ASRService.class);
        when(asr.transcribeDetailed(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("zh")))
                .thenReturn(new AsrResult("测试转写", null, null, null, List.of()));
        TaskWorkDispatcher dispatcher = new TaskWorkDispatcher(
                storage, asr,
                mock(AccessibilityService.class),
                mock(VideoVoiceSwapService.class), mock(SoundCloneService.class),
                mock(SpeakingPracticeService.class), mock(PPTService.class),
                mock(CoursewareProjectService.class), objectMapper,
                TaskResourceBulkheads.unrestricted());
        AsyncTaskService tasks = new AsyncTaskService(
                new InMemoryTaskRepository(), dispatcher, objectMapper, 1,
                Duration.ofMillis(5), Duration.ofSeconds(5), Duration.ofMillis(20),
                Duration.ofMillis(100), Duration.ofMillis(20), Duration.ofMillis(10),
                Duration.ofMillis(100), Duration.ofSeconds(1), 2,
                new SimpleMeterRegistry());
        MediaTaskService service = new MediaTaskService(
                tasks, storage, new UploadSecurityService());
        try {
            MockMultipartFile audio = new MockMultipartFile(
                    "file", "sample.wav", "audio/wav", TestMediaFiles.wav());
            AsyncTaskService.TaskSubmission submission =
                    service.submitAsr(audio, "zh", "alice");
            TaskRecord task = awaitTerminal(tasks, submission.taskId());
            String inputKey = objectMapper.readValue(
                    task.payload(), TaskPayloads.Asr.class).objectKey();

            try (var input = storage.open("alice", task.resultData())) {
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(json.contains("测试转写"));
            }
            awaitInputCleanup(storage, inputKey);
        } finally {
            tasks.shutdown();
        }
    }

    private TaskRecord awaitTerminal(AsyncTaskService tasks, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            TaskRecord task = tasks.get(id, "alice");
            if (task.status().terminal()) {
                assertTrue(task.status() == TaskStatus.SUCCESS);
                return task;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("任务未进入终态");
    }

    private void awaitInputCleanup(ManagedObjectStorageService storage, String inputKey)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            try {
                storage.requireMetadata("alice", inputKey);
                Thread.sleep(10);
            } catch (ResourceNotFoundException expected) {
                assertThrows(ResourceNotFoundException.class,
                        () -> storage.requireMetadata("alice", inputKey));
                return;
            }
        }
        throw new AssertionError("任务输入对象未清理");
    }
}
