package com.a09.tts.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("nodb")
public class InMemoryStoredObjectMetadataRepository implements StoredObjectMetadataRepository {
    private final ConcurrentHashMap<String, Metadata> objects = new ConcurrentHashMap<>();

    @Override
    public void save(Metadata metadata) {
        objects.put(metadata.objectKey(), metadata);
    }

    @Override
    public Optional<Metadata> findByKeyAndOwner(String objectKey, String owner) {
        Metadata metadata = objects.get(objectKey);
        return metadata != null && metadata.owner().equals(owner)
                ? Optional.of(metadata) : Optional.empty();
    }

    @Override
    public List<Metadata> findByOwnerAndPrefixAndSuffix(
            String owner, String prefix, String suffix, int offset, int limit) {
        return objects.values().stream()
                .filter(metadata -> metadata.owner().equals(owner))
                .filter(metadata -> metadata.objectKey().startsWith(prefix))
                .filter(metadata -> metadata.objectKey().endsWith(suffix))
                .sorted(Comparator.comparing(Metadata::createdAt).reversed()
                        .thenComparing(Metadata::objectKey, Comparator.reverseOrder()))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long sumSizeByOwner(String owner) {
        return objects.values().stream()
                .filter(metadata -> metadata.owner().equals(owner))
                .mapToLong(Metadata::size)
                .sum();
    }

    @Override
    public void delete(String objectKey, String owner) {
        objects.computeIfPresent(objectKey,
                (key, metadata) -> metadata.owner().equals(owner) ? null : metadata);
    }
}
