package com.a09.tts.storage;

import com.a09.tts.util.UploadUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorageService implements ObjectStorageService {
    private final Path root;

    public LocalObjectStorageService(@Value("${app.storage.local-root:./uploads}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredObject store(
            String objectKey,
            InputStream content,
            long size,
            String contentType,
            String checksumSha256) throws IOException {
        String key = ObjectStorageKeys.requireValid(objectKey);
        Path target = UploadUtils.resolveWithin(root, key);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        try {
            Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new StoredObject(key, provider(), bucket(), contentType, size, checksumSha256);
    }

    @Override
    public InputStream open(String objectKey) throws IOException {
        return Files.newInputStream(UploadUtils.resolveWithin(root,
                ObjectStorageKeys.requireValid(objectKey)));
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.isRegularFile(UploadUtils.resolveWithin(root,
                ObjectStorageKeys.requireValid(objectKey)));
    }

    @Override
    public void delete(String objectKey) throws IOException {
        UploadUtils.deleteWithin(root, ObjectStorageKeys.requireValid(objectKey));
    }

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public String bucket() {
        return "local";
    }
}
