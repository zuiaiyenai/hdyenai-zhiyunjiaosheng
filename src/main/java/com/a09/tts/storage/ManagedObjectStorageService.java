package com.a09.tts.storage;

import com.a09.tts.api.ResourceNotFoundException;
import com.a09.tts.storage.StoredObjectMetadataRepository.Metadata;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class ManagedObjectStorageService {
    private final ObjectStorageService storage;
    private final StoredObjectMetadataRepository metadataRepository;

    public ManagedObjectStorageService(
            ObjectStorageService storage,
            StoredObjectMetadataRepository metadataRepository) {
        this.storage = storage;
        this.metadataRepository = metadataRepository;
    }

    public Metadata store(String owner, String objectKey, InputStream content,
                          long size, String contentType, String checksumSha256) throws IOException {
        String normalizedOwner = normalizeOwner(owner);
        StoredObject stored = storage.store(
                ObjectStorageKeys.requireValid(objectKey), content, size, contentType, checksumSha256);
        Metadata metadata = new Metadata(
                stored.objectKey(), stored.provider(), stored.bucket(), stored.contentType(),
                stored.size(), stored.checksumSha256(), normalizedOwner, Instant.now());
        try {
            metadataRepository.save(metadata);
            return metadata;
        } catch (RuntimeException exception) {
            try {
                storage.delete(stored.objectKey());
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    public Metadata storeBytes(
            String owner, String objectKey, byte[] content, String contentType) throws IOException {
        return store(owner, objectKey, new ByteArrayInputStream(content), content.length,
                contentType, sha256(content));
    }

    public Metadata storeFile(
            String owner, String objectKey, Path path, String contentType) throws IOException {
        long size = Files.size(path);
        String checksum = sha256(path);
        try (InputStream input = Files.newInputStream(path)) {
            return store(owner, objectKey, input, size, contentType, checksum);
        }
    }

    public InputStream open(String owner, String objectKey) throws IOException {
        requireMetadata(owner, objectKey);
        return storage.open(objectKey);
    }

    public Path copyTo(String owner, String objectKey, Path target) throws IOException {
        requireMetadata(owner, objectKey);
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        try (InputStream input = storage.open(objectKey)) {
            Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    public <T> T withTemporaryCopy(
            String owner, String objectKey, TemporaryFileAction<T> action) throws Exception {
        String filename = ObjectStorageKeys.filename(objectKey);
        int dot = filename.lastIndexOf('.');
        String suffix = dot < 0 ? ".tmp" : filename.substring(dot);
        Path temporary = Files.createTempFile("fctts-object-", suffix);
        try {
            copyTo(owner, objectKey, temporary);
            return action.execute(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Metadata requireMetadata(String owner, String objectKey) {
        return metadataRepository.findByKeyAndOwner(
                        ObjectStorageKeys.requireValid(objectKey), normalizeOwner(owner))
                .orElseThrow(() -> new ResourceNotFoundException("存储对象不存在或无权访问"));
    }

    public List<Metadata> list(String owner, String prefix) {
        return metadataRepository.findByOwnerAndPrefix(
                normalizeOwner(owner), ObjectStorageKeys.requirePrefix(prefix));
    }

    public long usedBytes(String owner) {
        return metadataRepository.sumSizeByOwner(normalizeOwner(owner));
    }

    public void delete(String owner, String objectKey) throws IOException {
        Metadata metadata = requireMetadata(owner, objectKey);
        storage.delete(metadata.objectKey());
        metadataRepository.delete(metadata.objectKey(), metadata.owner());
    }

    private String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream ignored = new DigestInputStream(input, digest)) {
                ignored.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String normalizeOwner(String owner) {
        return owner == null || owner.isBlank() ? "anonymous" : owner;
    }

    @FunctionalInterface
    public interface TemporaryFileAction<T> {
        T execute(Path path) throws Exception;
    }
}
