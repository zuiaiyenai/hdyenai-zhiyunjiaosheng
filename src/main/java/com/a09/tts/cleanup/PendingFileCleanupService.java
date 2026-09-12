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

@Service
public class PendingFileCleanupService {
    public static final String VOICE_STORAGE = "VOICE";
    public static final String VOICE_OBJECT_STORAGE = "VOICE_OBJECT";
    private static final Logger log = LoggerFactory.getLogger(PendingFileCleanupService.class);
    private final PendingFileCleanupRepository repository;
    private final ObjectStorageService objectStorage;
    private final Path legacyVoiceRoot;

    @Autowired
    public PendingFileCleanupService(
            PendingFileCleanupRepository repository,
            ObjectStorageService objectStorage,
            @org.springframework.beans.factory.annotation.Value("${app.upload-dir}") String uploadDir) {
        this.repository = repository;
        this.objectStorage = objectStorage;
        this.legacyVoiceRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    PendingFileCleanupService(PendingFileCleanupRepository repository, String localRoot) {
        this(repository, new LocalObjectStorageService(localRoot), localRoot);
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
        for (PendingFileCleanup entry : repository.findBatch(100)) {
            try {
                requireSupported(entry.storageType());
                delete(entry.storageType(), entry.relativePath());
                repository.delete(entry.id());
            } catch (IllegalArgumentException exception) {
                repository.delete(entry.id());
                log.warn("丢弃超出存储根目录的待清理记录: cleanupId={}", entry.id());
            } catch (Exception exception) {
                repository.markFailed(entry.id(), "文件清理失败");
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
