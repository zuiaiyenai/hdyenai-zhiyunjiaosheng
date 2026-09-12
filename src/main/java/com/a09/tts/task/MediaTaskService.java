package com.a09.tts.task;

import com.a09.tts.api.AsrResult;
import com.a09.tts.api.ResourceNotFoundException;
import com.a09.tts.api.VideoSubtitlePreview;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.security.UploadSecurityService.Type;
import com.a09.tts.service.ASRService;
import com.a09.tts.service.AccessibilityService;
import com.a09.tts.service.PPTService;
import com.a09.tts.service.SoundCloneService;
import com.a09.tts.service.SpeakingPracticeService;
import com.a09.tts.service.VideoVoiceSwapService;
import com.a09.tts.storage.ManagedObjectStorageService;
import com.a09.tts.storage.ObjectStorageKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

@Service
public class MediaTaskService {
    private static final Logger log = LoggerFactory.getLogger(MediaTaskService.class);

    private final AsyncTaskService tasks;
    private final ManagedObjectStorageService objectStorage;
    private final UploadSecurityService uploadSecurity;
    private final ASRService asrService;
    private final AccessibilityService accessibilityService;
    private final VideoVoiceSwapService videoService;
    private final SoundCloneService soundCloneService;
    private final SpeakingPracticeService speakingPracticeService;
    private final PPTService pptService;
    private final ObjectMapper objectMapper;

    public MediaTaskService(AsyncTaskService tasks,
                            ManagedObjectStorageService objectStorage,
                            UploadSecurityService uploadSecurity,
                            ASRService asrService,
                            AccessibilityService accessibilityService,
                            VideoVoiceSwapService videoService,
                            SoundCloneService soundCloneService,
                            SpeakingPracticeService speakingPracticeService,
                            PPTService pptService,
                            ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.objectStorage = objectStorage;
        this.uploadSecurity = uploadSecurity;
        this.asrService = asrService;
        this.accessibilityService = accessibilityService;
        this.videoService = videoService;
        this.soundCloneService = soundCloneService;
        this.speakingPracticeService = speakingPracticeService;
        this.pptService = pptService;
        this.objectMapper = objectMapper;
    }

    public AsyncTaskService.TaskSubmission submitAsr(
            MultipartFile file, String language, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.AUDIO, owner);
        return submit(upload, "ASR_TRANSCRIBE", dedup(upload, language), () ->
                objectStorage.withTemporaryCopy(owner, upload.objectKey(), path -> {
                    AsrResult result = asrService.transcribeDetailed(path.toString(), language);
                    return storeJson(owner, upload, "asr-result.json", result);
                }));
    }

    public AsyncTaskService.TaskSubmission submitVoiceNote(
            MultipartFile file, String title, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.AUDIO, owner);
        return submit(upload, "VOICE_NOTE", dedup(upload, title), () -> {
            Object result = accessibilityService.saveVoiceNoteFromObject(
                    upload.objectKey(), upload.originalFilename(), title, owner);
            return storeJson(owner, upload, "voice-note.json", result);
        });
    }

    public AsyncTaskService.TaskSubmission submitVideoSubtitles(
            MultipartFile file, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.VIDEO, owner);
        return submit(upload, "VIDEO_SUBTITLES", dedup(upload, "zh"), () ->
                objectStorage.withTemporaryCopy(owner, upload.objectKey(), path -> {
                    VideoSubtitlePreview result = videoService.generateSubtitlePreview(path.toString());
                    return storeJson(owner, upload, "subtitles.json", result);
                }));
    }

    public AsyncTaskService.TaskSubmission submitVideoSwap(
            MultipartFile file, String voiceType, String transcript, String subtitles,
            boolean includeSubtitles, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.VIDEO, owner);
        return submit(upload, "VIDEO_VOICE_SWAP",
                dedup(upload, voiceType, transcript, subtitles, includeSubtitles), () ->
                        objectStorage.withTemporaryCopy(owner, upload.objectKey(), path -> {
                            ResponseEntity<byte[]> response = videoService.processVideo(
                                    path.toString(), voiceType, 1.0, 1.0, 1.0,
                                    transcript, subtitles, includeSubtitles);
                            byte[] body = requireBody(response, "视频换声未生成结果");
                            return storeBytes(owner, upload, "video.mp4", body, "video/mp4");
                        }));
    }

    public AsyncTaskService.TaskSubmission submitSoundClone(
            MultipartFile file, String promptText, String promptLang,
            String text, String textLang, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.AUDIO, owner);
        return submit(upload, "SOUND_CLONE",
                dedup(upload, promptText, promptLang, text, textLang), () ->
                        objectStorage.withTemporaryCopy(owner, upload.objectKey(), input -> {
                            ResponseEntity<StreamingResponseBody> response =
                                    soundCloneService.soundClone(
                                            promptText, promptLang, text, textLang,
                                            input.toString());
                            StreamingResponseBody body = requireBody(
                                    response, "声音克隆未生成结果");
                            Path output = Files.createTempFile("fctts-clone-", ".wav");
                            try {
                                try (OutputStream stream = Files.newOutputStream(output)) {
                                    body.writeTo(stream);
                                }
                                String key = resultKey(upload, "cloned.wav");
                                objectStorage.storeFile(owner, key, output, "audio/wav");
                                return key;
                            } finally {
                                Files.deleteIfExists(output);
                            }
                        }));
    }

    public AsyncTaskService.TaskSubmission submitSpeakingEvaluation(
            MultipartFile file, String referenceText, String mode, String sessionId,
            String language, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.AUDIO, owner);
        return submit(upload, "SPEAKING_EVALUATION",
                dedup(upload, referenceText, mode, sessionId, language), () ->
                        objectStorage.withTemporaryCopy(owner, upload.objectKey(), path -> {
                            ResponseEntity<?> response = speakingPracticeService.evaluate(
                                    path.toString(), referenceText, mode, sessionId, language, owner);
                            return storeJson(owner, upload, "evaluation.json",
                                    requireBody(response, "口语评测未生成结果"));
                        }));
    }

    public AsyncTaskService.TaskSubmission submitPptSummary(
            MultipartFile file, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.PRESENTATION, owner);
        return submit(upload, "PPT_SUMMARY", dedup(upload), () ->
                objectStorage.withTemporaryCopy(owner, upload.objectKey(), path -> {
                    String result = pptService.processPptAndGenerateContent(
                            path, upload.originalFilename());
                    return storeBytes(owner, upload, "summary.txt",
                            result.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "text/plain; charset=UTF-8");
                }));
    }

    private StagedUpload stage(MultipartFile file, Type type, String owner) throws IOException {
        uploadSecurity.validate(file, type);
        uploadSecurity.ensureQuota(objectStorage.usedBytes(owner), file.getSize());
        String uploadId = UUID.randomUUID().toString();
        String objectKey = ObjectStorageKeys.taskInput(owner, uploadId, file.getOriginalFilename());
        String checksum = uploadSecurity.sha256(file);
        try (InputStream input = file.getInputStream()) {
            objectStorage.store(owner, objectKey, input, file.getSize(),
                    file.getContentType(), checksum);
        }
        return new StagedUpload(owner, uploadId, objectKey, checksum,
                file.getOriginalFilename());
    }

    private AsyncTaskService.TaskSubmission submit(
            StagedUpload upload, String type, String deduplicationKey, TaskWork work) {
        try {
            AsyncTaskService.TaskSubmission submission = tasks.submit(
                    upload.owner(), type, deduplicationKey, work::execute,
                    () -> deleteQuietly(upload));
            if (submission.duplicate()) {
                deleteQuietly(upload);
            }
            return submission;
        } catch (RuntimeException exception) {
            deleteQuietly(upload);
            throw exception;
        }
    }

    private String storeJson(String owner, StagedUpload upload, String filename, Object value)
            throws IOException {
        return storeBytes(owner, upload, filename, objectMapper.writeValueAsBytes(value),
                "application/json");
    }

    private String storeBytes(String owner, StagedUpload upload, String filename,
                              byte[] content, String contentType) throws IOException {
        String key = resultKey(upload, filename);
        objectStorage.storeBytes(owner, key, content, contentType);
        return key;
    }

    private String resultKey(StagedUpload upload, String filename) {
        return ObjectStorageKeys.taskArtifact(upload.owner(), upload.uploadId(), filename);
    }

    private String dedup(StagedUpload upload, Object... values) {
        return upload.checksum() + ":" + Integer.toHexString(Objects.hash(values));
    }

    private <T> T requireBody(ResponseEntity<T> response, String message) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || response.getBody() == null) {
            throw new IllegalStateException(message);
        }
        return response.getBody();
    }

    private void deleteQuietly(StagedUpload upload) {
        try {
            objectStorage.delete(upload.owner(), upload.objectKey());
        } catch (ResourceNotFoundException ignored) {
            // Duplicate/rejected submissions may race with the task completion hook.
        } catch (Exception exception) {
            log.warn("异步媒体任务输入清理失败: objectKey={}", upload.objectKey(), exception);
        }
    }

    private record StagedUpload(
            String owner, String uploadId, String objectKey,
            String checksum, String originalFilename) {
    }

    @FunctionalInterface
    private interface TaskWork {
        String execute() throws Exception;
    }
}
