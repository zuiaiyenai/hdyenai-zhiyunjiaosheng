package com.a09.tts.task;

import com.a09.tts.api.AsrResult;
import com.a09.tts.api.ResourceNotFoundException;
import com.a09.tts.api.VideoSubtitlePreview;
import com.a09.tts.service.ASRService;
import com.a09.tts.service.AccessibilityService;
import com.a09.tts.service.CoursewareProjectService;
import com.a09.tts.service.PPTService;
import com.a09.tts.service.SoundCloneService;
import com.a09.tts.service.SpeakingPracticeService;
import com.a09.tts.service.VideoVoiceSwapService;
import com.a09.tts.storage.ManagedObjectStorageService;
import com.a09.tts.storage.ObjectStorageKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Service
public class TaskWorkDispatcher implements TaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(TaskWorkDispatcher.class);
    private static final Set<String> OBJECT_RESULT_TYPES = Set.of(
            "ASR_TRANSCRIBE", "VOICE_NOTE", "VIDEO_SUBTITLES", "VIDEO_VOICE_SWAP",
            "SOUND_CLONE", "SPEAKING_EVALUATION", "PPT_SUMMARY");

    private final ManagedObjectStorageService objectStorage;
    private final ASRService asrService;
    private final AccessibilityService accessibilityService;
    private final VideoVoiceSwapService videoService;
    private final SoundCloneService soundCloneService;
    private final SpeakingPracticeService speakingPracticeService;
    private final PPTService pptService;
    private final CoursewareProjectService coursewareService;
    private final ObjectMapper objectMapper;
    private final TaskResourceBulkheads bulkheads;

    public TaskWorkDispatcher(
            ManagedObjectStorageService objectStorage, ASRService asrService,
            AccessibilityService accessibilityService, VideoVoiceSwapService videoService,
            SoundCloneService soundCloneService, SpeakingPracticeService speakingPracticeService,
            PPTService pptService, CoursewareProjectService coursewareService,
            ObjectMapper objectMapper, TaskResourceBulkheads bulkheads) {
        this.objectStorage = objectStorage;
        this.asrService = asrService;
        this.accessibilityService = accessibilityService;
        this.videoService = videoService;
        this.soundCloneService = soundCloneService;
        this.speakingPracticeService = speakingPracticeService;
        this.pptService = pptService;
        this.coursewareService = coursewareService;
        this.objectMapper = objectMapper;
        this.bulkheads = bulkheads;
    }

    @Override
    public String execute(TaskRecord task) throws Exception {
        try (TaskResourceBulkheads.Permit ignored =
                     bulkheads.acquire(TaskResourceProfiles.resourcesFor(task.type()))) {
            return switch (task.type()) {
                case "ASR_TRANSCRIBE" -> executeAsr(task);
                case "VOICE_NOTE" -> executeVoiceNote(task);
                case "VIDEO_SUBTITLES" -> executeVideoSubtitles(task);
                case "VIDEO_VOICE_SWAP" -> executeVideoSwap(task);
                case "SOUND_CLONE" -> executeSoundClone(task);
                case "SPEAKING_EVALUATION" -> executeSpeakingEvaluation(task);
                case "PPT_SUMMARY" -> executePptSummary(task);
                case "COURSEWARE_CREATE" -> executeCoursewareCreate(task);
                case "COURSEWARE_OPTIMIZE" -> executeCoursewareOptimize(task);
                case "COURSEWARE_AUDIO" -> executeCoursewareAudio(task);
                case "COURSEWARE_VIDEO" -> executeCoursewareVideo(task);
                default -> throw new IllegalArgumentException("未知任务类型: " + task.type());
            };
        }
    }

    @Override
    public void cleanup(TaskRecord task) {
        if (!OBJECT_RESULT_TYPES.contains(task.type())) {
            return;
        }
        try {
            JsonNode payload = objectMapper.readTree(task.payload());
            String objectKey = payload.path("objectKey").asText(null);
            if (objectKey != null) {
                deleteQuietly(task.owner(), objectKey, "任务输入");
            }
        } catch (Exception exception) {
            log.warn("无法解析任务输入清理信息: taskId={}", task.id(), exception);
        }
    }

    @Override
    public void cleanupUncommittedResult(TaskRecord task, String resultData) {
        if (resultData != null && OBJECT_RESULT_TYPES.contains(task.type())) {
            deleteQuietly(task.owner(), resultData, "未提交任务结果");
        }
    }

    private String executeAsr(TaskRecord task) throws Exception {
        TaskPayloads.Asr payload = payload(task, TaskPayloads.Asr.class);
        return objectStorage.withTemporaryCopy(task.owner(), payload.objectKey(), path -> {
            AsrResult result = asrService.transcribeDetailed(path.toString(), payload.language());
            return storeJson(task, payload.uploadId(), "asr-result.json", result);
        });
    }

    private String executeVoiceNote(TaskRecord task) throws Exception {
        TaskPayloads.VoiceNote payload = payload(task, TaskPayloads.VoiceNote.class);
        Object result = accessibilityService.saveVoiceNoteFromObject(
                payload.objectKey(), payload.originalFilename(), payload.title(),
                task.owner(), payload.noteId());
        return storeJson(task, payload.uploadId(), "voice-note.json", result);
    }

    private String executeVideoSubtitles(TaskRecord task) throws Exception {
        TaskPayloads.VideoSubtitles payload = payload(task, TaskPayloads.VideoSubtitles.class);
        return objectStorage.withTemporaryCopy(task.owner(), payload.objectKey(), path -> {
            VideoSubtitlePreview result = videoService.generateSubtitlePreview(path.toString());
            return storeJson(task, payload.uploadId(), "subtitles.json", result);
        });
    }

    private String executeVideoSwap(TaskRecord task) throws Exception {
        TaskPayloads.VideoSwap payload = payload(task, TaskPayloads.VideoSwap.class);
        return objectStorage.withTemporaryCopy(task.owner(), payload.objectKey(), path -> {
            ResponseEntity<byte[]> response = videoService.processVideo(
                    path.toString(), payload.voiceType(), 1.0, 1.0, 1.0,
                    payload.transcript(), payload.subtitles(), payload.includeSubtitles());
            byte[] body = requireBody(response, "视频换声未生成结果");
            return storeBytes(task, payload.uploadId(), "video.mp4", body, "video/mp4");
        });
    }

    private String executeSoundClone(TaskRecord task) throws Exception {
        TaskPayloads.SoundClone payload = payload(task, TaskPayloads.SoundClone.class);
        return objectStorage.withTemporaryCopy(task.owner(), payload.objectKey(), input -> {
            ResponseEntity<StreamingResponseBody> response = soundCloneService.soundClone(
                    payload.promptText(), payload.promptLang(), payload.text(),
                    payload.textLang(), input.toString());
            StreamingResponseBody body = requireBody(response, "声音克隆未生成结果");
            Path output = Files.createTempFile("fctts-clone-", ".wav");
            try {
                try (OutputStream stream = Files.newOutputStream(output)) {
                    body.writeTo(stream);
                }
                String key = resultKey(task, payload.uploadId(), "cloned.wav");
                objectStorage.storeFile(task.owner(), key, output, "audio/wav");
                return key;
            } finally {
                Files.deleteIfExists(output);
            }
        });
    }

    private String executeSpeakingEvaluation(TaskRecord task) throws Exception {
        TaskPayloads.SpeakingEvaluation payload =
                payload(task, TaskPayloads.SpeakingEvaluation.class);
        return objectStorage.withTemporaryCopy(task.owner(), payload.objectKey(), path -> {
            ResponseEntity<?> response = speakingPracticeService.evaluate(
                    path.toString(), payload.referenceText(), payload.mode(),
                    payload.sessionId(), payload.language(), task.owner());
            return storeJson(task, payload.uploadId(), "evaluation.json",
                    requireBody(response, "口语评测未生成结果"));
        });
    }

    private String executePptSummary(TaskRecord task) throws Exception {
        TaskPayloads.PptSummary payload = payload(task, TaskPayloads.PptSummary.class);
        return objectStorage.withTemporaryCopy(task.owner(), payload.objectKey(), path -> {
            String result = pptService.processPptAndGenerateContent(
                    path, payload.originalFilename());
            return storeBytes(task, payload.uploadId(), "summary.txt",
                    result.getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8");
        });
    }

    private String executeCoursewareCreate(TaskRecord task) throws IOException {
        TaskPayloads.CoursewareCreate payload = payload(task, TaskPayloads.CoursewareCreate.class);
        return coursewareService.processPrepared(payload.projectId(), task.owner()).id();
    }

    private String executeCoursewareOptimize(TaskRecord task) throws IOException {
        TaskPayloads.CoursewareOptimize payload =
                payload(task, TaskPayloads.CoursewareOptimize.class);
        return coursewareService.optimize(
                payload.projectId(), task.owner(), payload.instruction()).id();
    }

    private String executeCoursewareAudio(TaskRecord task) throws IOException {
        TaskPayloads.CoursewareAudio payload = payload(task, TaskPayloads.CoursewareAudio.class);
        return coursewareService.generateAudio(payload.projectId(), task.owner(), payload.voice(),
                payload.speed(), payload.pitch(), payload.rhythm()).id();
    }

    private String executeCoursewareVideo(TaskRecord task) throws IOException {
        TaskPayloads.CoursewareVideo payload = payload(task, TaskPayloads.CoursewareVideo.class);
        return coursewareService.generateVideo(payload.projectId(), task.owner()).id();
    }

    private <T> T payload(TaskRecord task, Class<T> type) throws IOException {
        return objectMapper.readValue(task.payload(), type);
    }

    private String storeJson(TaskRecord task, String uploadId, String filename, Object value)
            throws IOException {
        return storeBytes(task, uploadId, filename, objectMapper.writeValueAsBytes(value),
                "application/json");
    }

    private String storeBytes(TaskRecord task, String uploadId, String filename,
                              byte[] content, String contentType) throws IOException {
        String key = resultKey(task, uploadId, filename);
        objectStorage.storeBytes(task.owner(), key, content, contentType);
        return key;
    }

    private String resultKey(TaskRecord task, String uploadId, String filename) {
        return ObjectStorageKeys.taskArtifact(
                task.owner(), uploadId, "attempt-" + task.attempts() + "-" + filename);
    }

    private <T> T requireBody(ResponseEntity<T> response, String message) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || response.getBody() == null) {
            throw new IllegalStateException(message);
        }
        return response.getBody();
    }

    private void deleteQuietly(String owner, String objectKey, String description) {
        try {
            objectStorage.delete(owner, objectKey);
        } catch (ResourceNotFoundException ignored) {
            // Retried and cancelled tasks may converge on the same deterministic key.
        } catch (Exception exception) {
            log.warn("{}清理失败: objectKey={}", description, objectKey, exception);
        }
    }
}
