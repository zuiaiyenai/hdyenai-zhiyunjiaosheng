package com.a09.tts.controller;

import com.a09.tts.pojo.Voice;
import com.a09.tts.util.UploadUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/voice_library")
@Profile("nodb")
public class VoiceNoDbController {
    private final Map<Integer, Voice> voices = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final String uploadDir;

    public VoiceNoDbController(
            @org.springframework.beans.factory.annotation.Value("${app.upload-dir}") String uploadDir) {
        this.uploadDir = uploadDir;
        addDefault("知性女声", "教育教学");
        addDefault("沉稳男声", "知识讲解");
        addDefault("活力童声", "少儿阅读");
    }

    @GetMapping("/list")
    public List<Voice> list(HttpServletRequest request) {
        String username = username(request);
        return voices.values().stream()
                .filter(voice -> Boolean.TRUE.equals(voice.getPublicVisible())
                        || username.equals(voice.getOwnerUsername()))
                .sorted(Comparator.comparing(Voice::getVoiceId))
                .toList();
    }

    @GetMapping("/search")
    public List<Voice> search(@RequestParam("name") String name, HttpServletRequest request) {
        String keyword = name == null ? "" : name.trim().toLowerCase();
        return list(request).stream()
                .filter(voice -> voice.getVoiceName() != null
                        && voice.getVoiceName().toLowerCase().contains(keyword))
                .toList();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Voice> upload(
            @RequestParam("name") String name,
            @RequestParam(value = "scene", defaultValue = "") String scene,
            @RequestParam(value = "public", defaultValue = "false") boolean publicVisible,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws Exception {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("音色名称不能为空");
        }
        Path saved = UploadUtils.save(file, Path.of(uploadDir),
                Set.of(".wav", ".mp3", ".m4a", ".flac", ".ogg"));
        Voice voice = new Voice();
        voice.setVoiceId(sequence.incrementAndGet());
        voice.setVoiceName(name.trim());
        voice.setApplicationScene(scene);
        voice.setFilePath(saved.toString());
        voice.setMimeType(file.getContentType());
        voice.setPublicVisible(publicVisible);
        voice.setOwnerUsername(username(request));
        voice.setCreatedAt(LocalDateTime.now());
        voices.put(voice.getVoiceId(), voice);
        return ResponseEntity.status(HttpStatus.CREATED).body(voice);
    }

    @PutMapping("/update")
    public ResponseEntity<?> update(@RequestBody Voice incoming, HttpServletRequest request) {
        Voice current = voices.get(incoming.getVoiceId());
        if (current == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canManage(current, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (incoming.getVoiceName() != null && !incoming.getVoiceName().isBlank()) {
            current.setVoiceName(incoming.getVoiceName().trim());
        }
        current.setApplicationScene(incoming.getApplicationScene());
        if (incoming.getPublicVisible() != null) {
            current.setPublicVisible(incoming.getPublicVisible());
        }
        return ResponseEntity.ok(current);
    }

    @DeleteMapping("/delete/{voiceId}")
    public ResponseEntity<?> delete(@PathVariable int voiceId, HttpServletRequest request) throws Exception {
        Voice voice = voices.get(voiceId);
        if (voice == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canManage(voice, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        voices.remove(voiceId);
        if (voice.getFilePath() != null) {
            Files.deleteIfExists(Path.of(voice.getFilePath()));
        }
        return ResponseEntity.ok(Map.of("code", 200, "msg", "音色已删除"));
    }

    @GetMapping("/{voiceId}/audio")
    public ResponseEntity<byte[]> preview(@PathVariable int voiceId, HttpServletRequest request) throws Exception {
        Voice voice = voices.get(voiceId);
        if (voice == null || voice.getFilePath() == null || !Files.exists(Path.of(voice.getFilePath()))) {
            return ResponseEntity.notFound().build();
        }
        if (!Boolean.TRUE.equals(voice.getPublicVisible())
                && !username(request).equals(voice.getOwnerUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Path path = Path.of(voice.getFilePath());
        MediaType contentType = voice.getMimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(voice.getMimeType());
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(path.getFileName().toString()).build().toString())
                .body(Files.readAllBytes(path));
    }

    private void addDefault(String name, String scene) {
        Voice voice = new Voice();
        voice.setVoiceId(sequence.incrementAndGet());
        voice.setVoiceName(name);
        voice.setApplicationScene(scene);
        voice.setPublicVisible(true);
        voice.setOwnerUsername("system");
        voice.setCreatedAt(LocalDateTime.now());
        voices.put(voice.getVoiceId(), voice);
    }

    private boolean canManage(Voice voice, HttpServletRequest request) {
        String username = username(request);
        return username.equals(voice.getOwnerUsername()) || "admin".equals(username);
    }

    private String username(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "anonymous" : value.toString();
    }
}
