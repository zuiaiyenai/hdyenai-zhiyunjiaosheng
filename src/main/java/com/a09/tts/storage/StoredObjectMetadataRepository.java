package com.a09.tts.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StoredObjectMetadataRepository {
    void save(Metadata metadata);

    Optional<Metadata> findByKeyAndOwner(String objectKey, String owner);

    List<Metadata> findByOwnerAndPrefix(String owner, String prefix);

    long sumSizeByOwner(String owner);

    void delete(String objectKey, String owner);

    record Metadata(
            String objectKey,
            String provider,
            String bucket,
            String contentType,
            long size,
            String checksumSha256,
            String owner,
            Instant createdAt) {
    }
}
