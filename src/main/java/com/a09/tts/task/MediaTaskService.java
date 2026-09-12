package com.a09.tts.task;

import com.a09.tts.api.ResourceNotFoundException;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.security.UploadSecurityService.Type;
import com.a09.tts.storage.ManagedObjectStorageService;
import com.a09.tts.storage.ObjectStorageKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

@Service
public class MediaTaskService {
    private static final Logger log = LoggerFactory.getLogger(MediaTaskService.class);
    private static final int RETRYABLE_ATTEMPTS = 3;
    private static final int NON_IDEMPOTENT_ATTEMPTS = 1;

    private final AsyncTaskService tasks;
    private final ManagedObjectStorageService objectStorage;
    private final UploadSecurityService uploadSecurity;

    public MediaTaskService(
            AsyncTaskService tasks, ManagedObjectStorageService objectStorage,
            UploadSecurityService uploadSecurity) {
        this.tasks = tasks;
        this.objectStorage = objectStorage;
        this.uploadSecurity = uploadSecurity;
    }

    public AsyncTaskService.TaskSubmission submitAsr(
            MultipartFile file, String language, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.AUDIO, owner);
        return submit(upload, "ASR_TRANSCRIBE", dedup(upload, language),
                new TaskPayloads.Asr(upload.objectKey(), upload.uploadId(),
                        upload.originalFilename(), language), RETRYABLE_ATTEMPTS);
    }

    public AsyncTaskService.TaskSubmission submitVoiceNote(
            MultipartFile file, String title, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.AUDIO, owner);
        return submit(upload, "VOICE_NOTE", dedup(upload, title),
                new TaskPayloads.VoiceNote(upload.objectKey(), upload.uploadId(),
                        upload.originalFilename(), title, upload.uploadId()),
                NON_IDEMPOTENT_ATTEMPTS);
    }

    public AsyncTaskService.TaskSubmission submitVideoSubtitles(
            MultipartFile file, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.VIDEO, owner);
        return submit(upload, "VIDEO_SUBTITLES", dedup(upload, "zh"),
                new TaskPayloads.VideoSubtitles(upload.objectKey(), upload.uploadId(),
                        upload.originalFilename()), RETRYABLE_ATTEMPTS);
    }

    public AsyncTaskService.TaskSubmission submitVideoSwap(
            MultipartFile file, String voiceType, String transcript, String subtitles,
            boolean includeSubtitles, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.VIDEO, owner);
        return submit(upload, "VIDEO_VOICE_SWAP",
                dedup(upload, voiceType, transcript, subtitles, includeSubtitles),
                new TaskPayloads.VideoSwap(upload.objectKey(), upload.uploadId(),
                        upload.originalFilename(), voiceType, transcript, subtitles,
                        includeSubtitles), RETRYABLE_ATTEMPTS);
    }

    public AsyncTaskService.TaskSubmission submitSoundClone(
            MultipartFile file, String promptText, String promptLang,
            String text, String textLang, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.AUDIO, owner);
        return submit(upload, "SOUND_CLONE",
                dedup(upload, promptText, promptLang, text, textLang),
                new TaskPayloads.SoundClone(upload.objectKey(), upload.uploadId(),
                        upload.originalFilename(), promptText, promptLang, text, textLang),
                RETRYABLE_ATTEMPTS);
    }

    public AsyncTaskService.TaskSubmission submitSpeakingEvaluation(
            MultipartFile file, String referenceText, String mode, String sessionId,
            String language, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.AUDIO, owner);
        return submit(upload, "SPEAKING_EVALUATION",
                dedup(upload, referenceText, mode, sessionId, language),
                new TaskPayloads.SpeakingEvaluation(upload.objectKey(), upload.uploadId(),
                        upload.originalFilename(), referenceText, mode, sessionId, language),
                NON_IDEMPOTENT_ATTEMPTS);
    }

    public AsyncTaskService.TaskSubmission submitPptSummary(
            MultipartFile file, String owner) throws IOException {
        StagedUpload upload = stage(file, Type.PRESENTATION, owner);
        return submit(upload, "PPT_SUMMARY", dedup(upload),
                new TaskPayloads.PptSummary(upload.objectKey(), upload.uploadId(),
                        upload.originalFilename()), RETRYABLE_ATTEMPTS);
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
            StagedUpload upload, String type, String deduplicationKey,
            Object payload, int maxAttempts) {
        try {
            AsyncTaskService.TaskSubmission submission = tasks.submit(
                    upload.owner(), type, deduplicationKey, payload, maxAttempts);
            if (submission.duplicate()) {
                deleteQuietly(upload);
            }
            return submission;
        } catch (RuntimeException exception) {
            deleteQuietly(upload);
            throw exception;
        }
    }

    private String dedup(StagedUpload upload, Object... values) {
        return upload.checksum() + ":" + Integer.toHexString(Objects.hash(values));
    }

    private void deleteQuietly(StagedUpload upload) {
        try {
            objectStorage.delete(upload.owner(), upload.objectKey());
        } catch (ResourceNotFoundException ignored) {
            // A duplicate or rejected submission may already have converged on cleanup.
        } catch (Exception exception) {
            log.warn("异步媒体任务输入清理失败: objectKey={}", upload.objectKey(), exception);
        }
    }

    private record StagedUpload(
            String owner, String uploadId, String objectKey,
            String checksum, String originalFilename) {
    }
}
