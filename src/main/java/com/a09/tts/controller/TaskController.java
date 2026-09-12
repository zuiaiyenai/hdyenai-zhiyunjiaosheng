package com.a09.tts.controller;

import com.a09.tts.api.PageResult;
import com.a09.tts.task.AsyncTaskService;
import com.a09.tts.task.TaskRecord;
import com.a09.tts.task.TaskStatus;
import com.a09.tts.storage.ManagedObjectStorageService;
import com.a09.tts.storage.ObjectStorageKeys;
import com.a09.tts.storage.StoredObjectMetadataRepository.Metadata;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private static final Set<String> OBJECT_RESULT_TYPES = Set.of(
            "ASR_TRANSCRIBE", "VIDEO_SUBTITLES", "VIDEO_VOICE_SWAP",
            "SOUND_CLONE", "SPEAKING_EVALUATION", "PPT_SUMMARY", "VOICE_NOTE");
    private final AsyncTaskService taskService;
    private final ManagedObjectStorageService objectStorage;
    private final HttpServletRequest request;

    public TaskController(AsyncTaskService taskService,
                          ManagedObjectStorageService objectStorage,
                          HttpServletRequest request) {
        this.taskService = taskService;
        this.objectStorage = objectStorage;
        this.request = request;
    }

    @GetMapping("/{id}")
    public TaskView get(@PathVariable String id) {
        return view(taskService.get(id, currentUsername()));
    }

    @GetMapping
    public PageResult<TaskView> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        PageResult<TaskRecord> tasks = taskService.list(currentUsername(), page, size);
        return new PageResult<>(tasks.content().stream().map(this::view).toList(),
                tasks.page(), tasks.size(), tasks.hasNext());
    }

    @PostMapping("/{id}/cancel")
    public TaskView cancel(@PathVariable String id) {
        return view(taskService.cancel(id, currentUsername()));
    }

    @GetMapping("/{id}/result")
    public ResponseEntity<Resource> result(@PathVariable String id) throws IOException {
        String owner = currentUsername();
        TaskRecord task = taskService.get(id, owner);
        if (task.status() != TaskStatus.SUCCESS || task.resultData() == null) {
            throw new IllegalStateException("任务尚未生成可用结果");
        }
        if (!OBJECT_RESULT_TYPES.contains(task.type())) {
            throw new IllegalArgumentException("该任务类型没有对象结果");
        }
        Metadata metadata = objectStorage.requireMetadata(owner, task.resultData());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(metadata.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(metadata.contentType()));
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(ObjectStorageKeys.filename(metadata.objectKey()), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(metadata.size())
                .body(new InputStreamResource(objectStorage.open(owner, metadata.objectKey())));
    }

    private TaskView view(TaskRecord task) {
        return new TaskView(task.id(), task.type(), task.status().name(), task.progress(),
                task.resultData(), task.errorMessage(), task.createdAt(),
                task.startedAt(), task.finishedAt());
    }

    private String currentUsername() {
        Object username = request.getAttribute("username");
        return username == null ? "anonymous" : username.toString();
    }

    public record TaskView(
            String id,
            String type,
            String status,
            int progress,
            String resultData,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt
    ) {
    }
}
