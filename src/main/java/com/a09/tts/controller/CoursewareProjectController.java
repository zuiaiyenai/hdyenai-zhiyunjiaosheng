package com.a09.tts.controller;

import com.a09.tts.service.CoursewareProjectService;
import com.a09.tts.service.CoursewareProjectService.DownloadArtifact;
import com.a09.tts.service.CoursewareProjectService.ProjectView;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.nio.file.Files;

@RestController
@RequestMapping("/courseware/projects")
public class CoursewareProjectController {

    private final CoursewareProjectService projectService;
    private final HttpServletRequest request;

    public CoursewareProjectController(CoursewareProjectService projectService, HttpServletRequest request) {
        this.projectService = projectService;
        this.request = request;
    }

    @PostMapping
    public ProjectView create(@RequestParam("file") MultipartFile file) throws IOException {
        return projectService.create(file, currentUsername());
    }

    @GetMapping("/{id}")
    public ProjectView get(@PathVariable String id) {
        return projectService.get(id, currentUsername());
    }

    @PostMapping("/{id}/optimize")
    public ProjectView optimize(@PathVariable String id, @RequestBody OptimizeRequest request)
            throws IOException {
        return projectService.optimize(id, currentUsername(), request.instruction());
    }

    @PutMapping("/{id}/script")
    public ProjectView updateScript(@PathVariable String id, @RequestBody ScriptRequest request)
            throws IOException {
        return projectService.updateScript(id, currentUsername(), request.script());
    }

    @PostMapping("/{id}/audio")
    public ProjectView generateAudio(@PathVariable String id, @RequestBody AudioRequest request)
            throws IOException {
        return projectService.generateAudio(id, currentUsername(), request.voice(),
                effective(request.speed()), effective(request.pitch()), effective(request.rhythm()));
    }

    @PostMapping("/{id}/avatar")
    public ProjectView uploadAvatar(@PathVariable String id,
                                    @RequestParam("avatar") MultipartFile avatar) throws IOException {
        return projectService.uploadAvatar(id, currentUsername(), avatar);
    }

    @PostMapping("/{id}/video")
    public ProjectView generateVideo(@PathVariable String id) throws IOException {
        return projectService.generateVideo(id, currentUsername());
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
                .contentLength(Files.size(download.path()))
                .body(new FileSystemResource(download.path()));
    }

    private double effective(Double value) {
        return value == null ? 1.0 : value;
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
}
