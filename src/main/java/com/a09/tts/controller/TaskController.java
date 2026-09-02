package com.a09.tts.controller;

import com.a09.tts.api.PageResult;
import com.a09.tts.task.AsyncTaskService;
import com.a09.tts.task.TaskRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final AsyncTaskService taskService;
    private final HttpServletRequest request;

    public TaskController(AsyncTaskService taskService, HttpServletRequest request) {
        this.taskService = taskService;
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
