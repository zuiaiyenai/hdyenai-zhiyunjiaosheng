package com.a09.tts.cleanup;

import com.a09.tts.storage.LocalObjectStorageService;
import com.a09.tts.storage.ObjectStorageService;
import com.a09.tts.util.UploadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class PendingFileCleanupService {
    public static final String VOICE_STORAGE = "VOICE";
    public static final String VOICE_OBJECT_STORAGE = "VOICE_OBJECT";
    private static final Logger log = LoggerFactory.getLogger(PendingFileCleanupService.class);
    private final PendingFileCleanupRepository repository;
    private final ObjectStorageService objectStorage;
    private final Path legacyVoiceRoot;
    private final Duration claimTimeout;

    @Autowired
    public PendingFileCleanupService(
            PendingFileCleanupRepository repository,
            ObjectStorageService objectStorage,
            @org.springframework.beans.factory.annotation.Value("${app.upload-dir}") String uploadDir,
            @org.springframework.beans.factory.annotation.Value("${app.cleanup.claim-timeout:15m}")
            Duration claimTimeout) {
        this.repository = repository;
        this.objectStorage = objectStorage;
        this.legacyVoiceRoot = Path.of(uploadDir).toAbsolutePath().normalize();
        if (claimTimeout == null || claimTimeout.isZero() || claimTimeout.isNegative()) {
            throw new IllegalArgumentException("Cleanup claim timeout must be positive");
        }
        this.claimTimeout = claimTimeout;
    }

    PendingFileCleanupService(PendingFileCleanupRepository repository, String localRoot) {
        this(repository, new LocalObjectStorageService(localRoot), localRoot,
                Duration.ofMinutes(15));
    }

    public void deleteOrEnqueue(String storageType, String relativePath) {
        requireSupported(storageType);
        try {
            delete(storageType, relativePath);
        } catch (IllegalArgumentException exception) {
            log.warn("拒绝清理上传目录外的文件: storageType={}", storageType);
        } catch (Exception exception) {
            log.warn("业务记录已删除，文件将在后台重试清理: storageType={}, key={}",
                    storageType, relativePath);
            try {
                repository.enqueue(storageType, relativePath);
            } catch (Exception enqueueException) {
                log.error("待清理文件写入补偿队列失败: storageType={}, key={}",
                        storageType, relativePath, enqueueException);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.cleanup.retry-delay:5m}")
    public void retryPendingFiles() {
        Instant now = Instant.now();
        String claimToken = UUID.randomUUID().toString();
        for (PendingFileCleanup entry : repository.claimBatch(
                100, claimToken, now, now.minus(claimTimeout))) {
            try {
                requireSupported(entry.storageType());
                delete(entry.storageType(), entry.relativePath());
                repository.complete(entry.id(), claimToken);
            } catch (IllegalArgumentException exception) {
                repository.complete(entry.id(), claimToken);
                log.warn("丢弃非法待清理记录: cleanupId={}, reason={}",
                        entry.id(), exception.getMessage());
            } catch (Exception exception) {
                repository.markFailed(entry.id(), claimToken, "文件清理失败");
                log.warn("待清理文件重试失败: cleanupId={}", entry.id());
            }
        }
    }

    private void requireSupported(String storageType) {
        if (!VOICE_STORAGE.equals(storageType) && !VOICE_OBJECT_STORAGE.equals(storageType)) {
            throw new IllegalArgumentException("不支持的文件清理存储类型");
        }
    }

    private void delete(String storageType, String key) throws Exception {
        if (VOICE_OBJECT_STORAGE.equals(storageType)) {
            objectStorage.delete(key);
            return;
        }
        UploadUtils.deleteWithin(legacyVoiceRoot, key);
    }
}
