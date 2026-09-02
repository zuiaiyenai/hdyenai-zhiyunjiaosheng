package com.a09.tts.cleanup;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Profile("!nodb")
public class JdbcPendingFileCleanupRepository implements PendingFileCleanupRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcPendingFileCleanupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void enqueue(String storageType, String relativePath) {
        jdbcTemplate.update("""
                INSERT INTO pending_file_cleanup (storage_type, relative_path)
                VALUES (?, ?)
                """, storageType, relativePath);
    }

    @Override
    public List<PendingFileCleanup> findBatch(int limit) {
        return jdbcTemplate.query("""
                        SELECT cleanup_id, storage_type, relative_path, attempts, last_error,
                               created_at, updated_at
                        FROM pending_file_cleanup
                        ORDER BY attempts, updated_at, cleanup_id
                        LIMIT ?
                        """,
                (resultSet, rowNumber) -> new PendingFileCleanup(
                        resultSet.getLong("cleanup_id"),
                        resultSet.getString("storage_type"),
                        resultSet.getString("relative_path"),
                        resultSet.getInt("attempts"),
                        resultSet.getString("last_error"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()),
                limit);
    }

    @Override
    public void markFailed(long id, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE pending_file_cleanup
                SET attempts = attempts + 1, last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE cleanup_id = ?
                """, errorMessage, id);
    }

    @Override
    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM pending_file_cleanup WHERE cleanup_id = ?", id);
    }
}
