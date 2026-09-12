package com.a09.tts.controller;

import com.a09.tts.api.PageResult;
import com.a09.tts.api.Pagination;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.a09.tts.pojo.Voice;
import com.a09.tts.service.VoiceService;
import com.a09.tts.storage.ObjectStorageKeys;
import com.a09.tts.storage.ObjectStorageService;
import com.a09.tts.util.UploadUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/voice_library")
@Profile("!nodb")
public class VoiceController {

    private static final Logger log = LoggerFactory.getLogger(VoiceController.class);

    @Autowired
    private VoiceService voiceService;

    @Autowired
    private ObjectStorageService objectStorage;

    @org.springframework.beans.factory.annotation.Value("${app.upload-dir}")
    private String uploadDir;

    @GetMapping("/list")
    public ResponseEntity<?> listAllVoices(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request) {
        int pageNumber = Pagination.page(page);
        int pageSize = Pagination.size(size);
        List<Voice> window = voiceService.findVisibleVoices(
                username(request), Pagination.offset(pageNumber, pageSize), pageSize + 1);
        PageResult<Voice> result = PageResult.fromWindow(window, pageNumber, pageSize);
        return ResponseEntity.ok(page == null && size == null ? result.content() : result);
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchVoices(
            @RequestParam("name") String name,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request) {
        int pageNumber = Pagination.page(page);
        int pageSize = Pagination.size(size);
        List<Voice> window = voiceService.findVisibleVoiceByName(
                name, username(request), Pagination.offset(pageNumber, pageSize), pageSize + 1);
        PageResult<Voice> result = PageResult.fromWindow(window, pageNumber, pageSize);
        return ResponseEntity.ok(page == null && size == null ? result.content() : result);
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addVoice() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("code", 410, "msg", "该接口已停用，请使用 /voice_library/upload 上传声音文件"));
    }

    @PutMapping("/update")
    public ResponseEntity<Map<String, Object>> updateVoice(
            @RequestBody Voice voice, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        Voice current = voiceService.findById(voice.getVoiceId());
        if (current == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canManage(current, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        int res = voiceService.updateVoiceSample(voice);
        if (res == 1) {
            result.put("code", 200);
            result.put("msg", "声音样本更新成功");
            return ResponseEntity.ok(result);
        }
        result.put("code", 400);
        result.put("msg", "声音样本更新失败");
        return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
    }

    @DeleteMapping("/delete/{voiceId}")
    public ResponseEntity<Map<String, Object>> deleteVoice(
            @PathVariable int voiceId, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        Voice current = voiceService.findById(voiceId);
        if (current == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canManage(current, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        int res = voiceService.deleteVoiceById(voiceId);
        if (res == 1) {
            result.put("code", 200);
            result.put("msg", "声音样本删除成功");
            return ResponseEntity.ok(result);
        }
        result.put("code", 400);
        result.put("msg", "声音样本删除失败");
        return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Voice> uploadVoice(
            @RequestParam("name") String name,
            @RequestParam(value = "scene", defaultValue = "") String scene,
            @RequestParam(value = "public", defaultValue = "false") boolean publicVisible,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws Exception {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("声音名称不能为空");
        }
        String owner = String.valueOf(request.getAttribute("username"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(voiceService.upload(name, scene, publicVisible, owner, file));
    }

    @GetMapping("/{voiceId}/audio")
    public ResponseEntity<Resource> preview(@PathVariable int voiceId, HttpServletRequest request) throws Exception {
        Voice voice = voiceService.findById(voiceId);
        if (voice == null || (voice.getObjectKey() == null && voice.getFilePath() == null)) {
            return ResponseEntity.notFound().build();
        }
        String username = String.valueOf(request.getAttribute("username"));
        if (!Boolean.TRUE.equals(voice.getPublicVisible()) && !username.equals(voice.getOwnerUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Resource audio;
        String filename;
        try {
            if (voice.getObjectKey() != null && !voice.getObjectKey().isBlank()) {
                if (!matchesActiveStorage(voice) || !objectStorage.exists(voice.getObjectKey())) {
                    return ResponseEntity.notFound().build();
                }
                filename = ObjectStorageKeys.filename(voice.getObjectKey());
                audio = resource(objectStorage.open(voice.getObjectKey()), filename, voice.getFileSize());
            } else {
                Path path = resolveLegacyVoicePath(voice);
                if (!Files.isRegularFile(path)) {
                    return ResponseEntity.notFound().build();
                }
                filename = path.getFileName().toString();
                audio = resource(Files.newInputStream(path), filename, Files.size(path));
            }
        } catch (IllegalArgumentException exception) {
            log.warn("拒绝读取上传目录外的声音文件，voiceId={}", voiceId);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(voice.getMimeType() == null ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(voice.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(filename).build().toString())
                .body(audio);
    }

    private Path resolveLegacyVoicePath(Voice voice) {
        Path root = Path.of(uploadDir);
        try {
            Path current = UploadUtils.resolveWithin(root, voice.getFilePath());
            if (Files.isRegularFile(current)) {
                return current;
            }
        } catch (IllegalArgumentException exception) {
            if (!Boolean.TRUE.equals(voice.getPublicVisible())) {
                throw exception;
            }
        }
        if (!Boolean.TRUE.equals(voice.getPublicVisible())) {
            throw new IllegalArgumentException("声音文件不存在");
        }
        String portablePath = voice.getFilePath().replace('\\', '/');
        String legacyPrefix = "uploads/voice_samples/";
        int prefixIndex = portablePath.lastIndexOf(legacyPrefix);
        if (prefixIndex < 0) {
            throw new IllegalArgumentException("声音文件不存在");
        }
        Path relocated = UploadUtils.resolveWithin(root,
                portablePath.substring(prefixIndex + legacyPrefix.length()));
        if (!Files.isRegularFile(relocated)) {
            throw new IllegalArgumentException("声音文件不存在");
        }
        log.info("兼容旧版公开声音路径，voiceId={}", voice.getVoiceId());
        return relocated;
    }

    private boolean matchesActiveStorage(Voice voice) {
        return (voice.getStorageProvider() == null
                || objectStorage.provider().equals(voice.getStorageProvider()))
                && (voice.getStorageBucket() == null
                || objectStorage.bucket().equals(voice.getStorageBucket()));
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

    private boolean canManage(Voice voice, HttpServletRequest request) {
        String username = username(request);
        return "admin".equals(username) || username.equals(voice.getOwnerUsername());
    }

    private String username(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "anonymous" : value.toString();
    }
}
