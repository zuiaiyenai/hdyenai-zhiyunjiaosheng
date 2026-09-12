package com.a09.tts.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!nodb")
public class JdbcStoredObjectMetadataRepository implements StoredObjectMetadataRepository {
    private static final String COLUMNS = """
            object_key, storage_provider, storage_bucket, content_type, object_size,
            checksum_sha256, owner_username, created_at
            """;
    private final JdbcTemplate jdbcTemplate;

    public JdbcStoredObjectMetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(Metadata metadata) {
        jdbcTemplate.update("""
                INSERT INTO stored_object_metadata (
                    object_key, storage_provider, storage_bucket, content_type, object_size,
                    checksum_sha256, owner_username, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    storage_provider = VALUES(storage_provider),
                    storage_bucket = VALUES(storage_bucket),
                    content_type = VALUES(content_type),
                    object_size = VALUES(object_size),
                    checksum_sha256 = VALUES(checksum_sha256),
                    owner_username = VALUES(owner_username)
                """, metadata.objectKey(), metadata.provider(), metadata.bucket(),
                metadata.contentType(), metadata.size(), metadata.checksumSha256(),
                metadata.owner(), Timestamp.from(metadata.createdAt()));
    }

    @Override
    public Optional<Metadata> findByKeyAndOwner(String objectKey, String owner) {
        List<Metadata> matches = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM stored_object_metadata "
                        + "WHERE object_key = ? AND owner_username = ?",
                this::map, objectKey, owner);
        return matches.stream().findFirst();
    }

    @Override
    public List<Metadata> findByOwnerAndPrefix(String owner, String prefix) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM stored_object_metadata "
                        + "WHERE owner_username = ? AND object_key LIKE ? "
                        + "ORDER BY created_at DESC, object_key DESC",
                this::map, owner, prefix + "%");
    }

    @Override
    public long sumSizeByOwner(String owner) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(object_size), 0) FROM stored_object_metadata "
                        + "WHERE owner_username = ?", Long.class, owner);
        return total == null ? 0 : total;
    }

    @Override
    public void delete(String objectKey, String owner) {
        jdbcTemplate.update(
                "DELETE FROM stored_object_metadata WHERE object_key = ? AND owner_username = ?",
                objectKey, owner);
    }

    private Metadata map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Metadata(
                resultSet.getString("object_key"),
                resultSet.getString("storage_provider"),
                resultSet.getString("storage_bucket"),
                resultSet.getString("content_type"),
                resultSet.getLong("object_size"),
                resultSet.getString("checksum_sha256"),
                resultSet.getString("owner_username"),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
