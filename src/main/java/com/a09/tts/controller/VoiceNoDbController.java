package com.a09.tts.controller;

import com.a09.tts.api.Pagination;
import com.a09.tts.pojo.Voice;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.security.UploadSecurityService.Type;
import com.a09.tts.storage.LocalObjectStorageService;
import com.a09.tts.storage.ObjectStorageKeys;
import com.a09.tts.storage.ObjectStorageService;
import com.a09.tts.storage.StoredObject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
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

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/voice_library")
@Profile("nodb")
public class VoiceNoDbController {
    private final Map<Integer, Voice> voices = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final UploadSecurityService uploadSecurity;
    private final ObjectStorageService objectStorage;

    @org.springframework.beans.factory.annotation.Autowired
    public VoiceNoDbController(
            UploadSecurityService uploadSecurity,
            ObjectStorageService objectStorage) {
        this.uploadSecurity = uploadSecurity;
        this.objectStorage = objectStorage;
        addDefault("知性女声", "教育教学");
        addDefault("沉稳男声", "知识讲解");
        addDefault("活力童声", "少儿阅读");
    }

    public VoiceNoDbController(String uploadDir) {
        this(new UploadSecurityService(), new LocalObjectStorageService(uploadDir));
    }

    @GetMapping("/list")
    public Object list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request) {
        List<Voice> visible = visibleVoices(request);
        return page == null && size == null ? visible : Pagination.slice(visible, page, size);
    }

    @GetMapping("/search")
    public Object search(
            @RequestParam("name") String name,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request) {
        String keyword = name == null ? "" : name.trim().toLowerCase();
        List<Voice> matches = visibleVoices(request).stream()
                .filter(voice -> voice.getVoiceName() != null
                        && voice.getVoiceName().toLowerCase().contains(keyword))
                .toList();
        return page == null && size == null ? matches : Pagination.slice(matches, page, size);
    }

    private List<Voice> visibleVoices(HttpServletRequest request) {
        String username = username(request);
        return voices.values().stream()
                .filter(voice -> Boolean.TRUE.equals(voice.getPublicVisible())
                        || username.equals(voice.getOwnerUsername()))
                .sorted(Comparator.comparing(Voice::getVoiceId))
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
        String owner = username(request);
        uploadSecurity.validate(file, Type.AUDIO);
        long usedBytes = voices.values().stream()
                .filter(voice -> owner.equals(voice.getOwnerUsername()))
                .map(Voice::getFileSize)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        uploadSecurity.ensureQuota(usedBytes, file.getSize());
        String key = ObjectStorageKeys.voiceSample(owner, file.getOriginalFilename());
        String checksum = uploadSecurity.sha256(file);
        StoredObject stored;
        try (var input = file.getInputStream()) {
            stored = objectStorage.store(
                    key, input, file.getSize(), file.getContentType(), checksum);
        }
        Voice voice = new Voice();
        voice.setVoiceId(sequence.incrementAndGet());
        voice.setVoiceName(name.trim());
        voice.setApplicationScene(scene);
        voice.setFilePath(stored.objectKey());
        voice.setObjectKey(stored.objectKey());
        voice.setStorageProvider(stored.provider());
        voice.setStorageBucket(stored.bucket());
        voice.setMimeType(stored.contentType());
        voice.setFileSize(stored.size());
        voice.setChecksumSha256(stored.checksumSha256());
        voice.setPublicVisible(publicVisible);
        voice.setOwnerUsername(owner);
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
        if (voice.getObjectKey() != null) {
            objectStorage.delete(voice.getObjectKey());
        }
        return ResponseEntity.ok(Map.of("code", 200, "msg", "音色已删除"));
    }

    @GetMapping("/{voiceId}/audio")
    public ResponseEntity<Resource> preview(@PathVariable int voiceId, HttpServletRequest request) throws Exception {
        Voice voice = voices.get(voiceId);
        if (voice == null || voice.getObjectKey() == null) {
            return ResponseEntity.notFound().build();
        }
        if (!Boolean.TRUE.equals(voice.getPublicVisible())
                && !username(request).equals(voice.getOwnerUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            if (!objectStorage.exists(voice.getObjectKey())) {
                return ResponseEntity.notFound().build();
            }
            String filename = ObjectStorageKeys.filename(voice.getObjectKey());
            Resource audio = resource(
                    objectStorage.open(voice.getObjectKey()), filename, voice.getFileSize());
            MediaType contentType = voice.getMimeType() == null
                    ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(voice.getMimeType());
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.inline().filename(filename).build().toString())
                    .body(audio);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    private Resource resource(InputStream input, String filename, Long size) {
        return new InputStreamResource(input) {
            @Override
            public String getFilename() {
                return filename;
            }

            @Override
            public long contentLength() {
                return size == null ? -1 : size;
            }
        };
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
