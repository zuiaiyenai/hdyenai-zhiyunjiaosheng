package com.a09.tts.controller;

import com.a09.tts.api.PageResult;
import com.a09.tts.service.CoursewareProjectService;
import com.a09.tts.service.CoursewareProjectService.DownloadArtifact;
import com.a09.tts.service.CoursewareProjectService.ProjectView;
import com.a09.tts.task.AsyncTaskService;
import com.a09.tts.task.AsyncTaskService.TaskSubmission;
import com.a09.tts.task.TaskPayloads;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/courseware/projects")
public class CoursewareProjectController {

    private final CoursewareProjectService projectService;
    private final AsyncTaskService taskService;
    private final HttpServletRequest request;

    public CoursewareProjectController(CoursewareProjectService projectService,
                                       AsyncTaskService taskService,
                                       HttpServletRequest request) {
        this.projectService = projectService;
        this.taskService = taskService;
        this.request = request;
    }

    @PostMapping
    public ResponseEntity<TaskSubmission> create(@RequestParam("file") MultipartFile file)
            throws IOException {
        String owner = currentUsername();
        ProjectView project = projectService.prepare(file, owner);
        try {
            return submit(() -> taskService.submit(owner, "COURSEWARE_CREATE", project.id(),
                    new TaskPayloads.CoursewareCreate(project.id()), 1));
        } catch (RuntimeException exception) {
            projectService.failPreparedSubmission(project.id(), owner, "课件生成任务提交失败");
            throw exception;
        }
    }

    @GetMapping("/{id}")
    public ProjectView get(@PathVariable String id) {
        return projectService.get(id, currentUsername());
    }

    @GetMapping
    public PageResult<ProjectView> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return projectService.list(currentUsername(), page, size);
    }

    @PostMapping("/{id}/optimize")
    public ResponseEntity<TaskSubmission> optimize(
            @PathVariable String id, @RequestBody OptimizeRequest request) {
        return optimizeTask(id, request);
    }

    @PutMapping("/{id}/script")
    public ProjectView updateScript(@PathVariable String id, @RequestBody ScriptRequest request)
            throws IOException {
        return projectService.updateScript(id, currentUsername(), request.script());
    }

    @PostMapping("/{id}/audio")
    public ResponseEntity<TaskSubmission> generateAudio(
            @PathVariable String id, @RequestBody AudioRequest request) {
        return generateAudioTask(id, request);
    }

    @PostMapping("/{id}/avatar")
    public ProjectView uploadAvatar(@PathVariable String id,
                                    @RequestParam("avatar") MultipartFile avatar) throws IOException {
        return projectService.uploadAvatar(id, currentUsername(), avatar);
    }

    @PostMapping("/{id}/video")
    public ResponseEntity<TaskSubmission> generateVideo(@PathVariable String id) {
        return generateVideoTask(id);
    }

    @PostMapping("/{id}/optimize/tasks")
    public ResponseEntity<TaskSubmission> optimizeTask(
            @PathVariable String id, @RequestBody OptimizeRequest body) {
        String owner = currentUsername();
        ProjectView project = projectService.get(id, owner);
        return submit(() -> taskService.submit(owner, "COURSEWARE_OPTIMIZE",
                id + ":" + project.revision() + ":"
                        + Integer.toHexString(java.util.Objects.hashCode(body.instruction())),
                new TaskPayloads.CoursewareOptimize(id, body.instruction()), 1));
    }

    @PostMapping("/{id}/audio/tasks")
    public ResponseEntity<TaskSubmission> generateAudioTask(
            @PathVariable String id, @RequestBody AudioRequest body) {
        String owner = currentUsername();
        ProjectView project = projectService.get(id, owner);
        double speed = effective(body.speed());
        double pitch = effective(body.pitch());
        double rhythm = effective(body.rhythm());
        return submit(() -> taskService.submit(owner, "COURSEWARE_AUDIO",
                id + ":" + project.revision() + ":" + body.voice()
                        + ":" + speed + ":" + pitch + ":" + rhythm,
                new TaskPayloads.CoursewareAudio(
                        id, body.voice(), speed, pitch, rhythm), 1));
    }

    @PostMapping("/{id}/video/tasks")
    public ResponseEntity<TaskSubmission> generateVideoTask(@PathVariable String id) {
        String owner = currentUsername();
        ProjectView project = projectService.get(id, owner);
        return submit(() -> taskService.submit(owner, "COURSEWARE_VIDEO",
                id + ":" + project.revision(),
                new TaskPayloads.CoursewareVideo(id), 1));
    }

    @GetMapping("/{id}/download/{artifact}")
    public ResponseEntity<Resource> download(@PathVariable String id,
                                             @PathVariable String artifact) throws IOException {
        DownloadArtifact download = projectService.download(id, currentUsername(), artifact);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(download.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(download.contentLength())
                .body(download.resource());
    }

    private double effective(Double value) {
        return value == null ? 1.0 : value;
    }

    private ResponseEntity<TaskSubmission> submit(TaskSupplier supplier) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(supplier.submit());
    }

    private String currentUsername() {
        Object username = request.getAttribute("username");
        return username == null ? "anonymous" : username.toString();
    }

    public record OptimizeRequest(String instruction) {
    }

    public record ScriptRequest(String script) {
    }

    public record AudioRequest(String voice, Double speed, Double pitch, Double rhythm) {
    }

    @FunctionalInterface
    private interface TaskSupplier {
        TaskSubmission submit();
    }
}
