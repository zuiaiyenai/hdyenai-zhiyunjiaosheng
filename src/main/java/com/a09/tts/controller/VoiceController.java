package com.a09.tts.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.a09.tts.pojo.Voice;
import com.a09.tts.service.VoiceService;
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

    @org.springframework.beans.factory.annotation.Value("${app.upload-dir}")
    private String uploadDir;

    @GetMapping("/list")
    public ResponseEntity<List<Voice>> listAllVoices(HttpServletRequest request) {
        List<Voice> voices = voiceService.findVisibleVoices(username(request));
        return ResponseEntity.ok(voices);
    }

    @GetMapping("/search")
    public ResponseEntity<List<Voice>> searchVoices(
            @RequestParam("name") String name, HttpServletRequest request) {
        List<Voice> voices = voiceService.findVisibleVoiceByName(name, username(request));
        return ResponseEntity.ok(voices);
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
        try {
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
            } else {
                result.put("code", 400);
                result.put("msg", "声音样本更新失败");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }
        } catch (Exception e) {
            log.error("更新声音样本失败", e);
            result.put("code", 500);
            result.put("msg", e.getMessage());
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @DeleteMapping("/delete/{voiceId}")
    public ResponseEntity<Map<String, Object>> deleteVoice(
            @PathVariable int voiceId, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
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
            } else {
                result.put("code", 400);
                result.put("msg", "声音样本删除失败");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }
        } catch (Exception e) {
            log.error("删除声音样本失败", e);
            result.put("code", 500);
            result.put("msg", e.getMessage());
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
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
    public ResponseEntity<byte[]> preview(@PathVariable int voiceId, HttpServletRequest request) throws Exception {
        Voice voice = voiceService.findById(voiceId);
        if (voice == null || voice.getFilePath() == null) {
            return ResponseEntity.notFound().build();
        }
        String username = String.valueOf(request.getAttribute("username"));
        if (!Boolean.TRUE.equals(voice.getPublicVisible()) && !username.equals(voice.getOwnerUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Path path;
        byte[] audio;
        try {
            path = UploadUtils.resolveWithin(Path.of(uploadDir), voice.getFilePath());
            if (!Files.isRegularFile(path)) {
                return ResponseEntity.notFound().build();
            }
            audio = Files.readAllBytes(path);
        } catch (IllegalArgumentException exception) {
            log.warn("拒绝读取上传目录外的声音文件，voiceId={}", voiceId);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(voice.getMimeType() == null ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(voice.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(path.getFileName().toString()).build().toString())
                .body(audio);
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
