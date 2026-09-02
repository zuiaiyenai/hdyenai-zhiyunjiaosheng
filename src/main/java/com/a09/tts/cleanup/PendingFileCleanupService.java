package com.a09.tts.cleanup;

import com.a09.tts.util.UploadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class PendingFileCleanupService {
    public static final String VOICE_STORAGE = "VOICE";
    private static final Logger log = LoggerFactory.getLogger(PendingFileCleanupService.class);
    private final PendingFileCleanupRepository repository;
    private final Path voiceRoot;

    public PendingFileCleanupService(
            PendingFileCleanupRepository repository,
            @Value("${app.upload-dir}") String uploadDir) {
        this.repository = repository;
        this.voiceRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public void deleteOrEnqueue(String storageType, String relativePath) {
        Path root = root(storageType);
        try {
            UploadUtils.resolveWithin(root, relativePath);
            UploadUtils.deleteWithin(root, relativePath);
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
                UploadUtils.deleteWithin(root(entry.storageType()), entry.relativePath());
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

    private Path root(String storageType) {
        if (VOICE_STORAGE.equals(storageType)) {
            return voiceRoot;
        }
        throw new IllegalArgumentException("不支持的文件清理存储类型");
    }
}
