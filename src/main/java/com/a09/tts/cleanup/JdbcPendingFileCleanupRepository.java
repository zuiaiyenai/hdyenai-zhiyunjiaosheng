package com.a09.tts.cleanup;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
                               claimed_by, claimed_at, created_at, updated_at
                        FROM pending_file_cleanup
                        ORDER BY attempts, updated_at, cleanup_id
                        LIMIT ?
                        """,
                this::map,
                limit);
    }

    @Override
    public List<PendingFileCleanup> claimBatch(
            int limit, String claimToken, Instant claimedAt, Instant staleBefore) {
        int updated = jdbcTemplate.update("""
                UPDATE pending_file_cleanup
                SET claimed_by = ?, claimed_at = ?, updated_at = updated_at
                WHERE claimed_at IS NULL OR claimed_at < ?
                ORDER BY attempts, updated_at, cleanup_id
                LIMIT ?
                """, claimToken, timestamp(claimedAt), timestamp(staleBefore), limit);
        if (updated == 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                        SELECT cleanup_id, storage_type, relative_path, attempts, last_error,
                               claimed_by, claimed_at, created_at, updated_at
                        FROM pending_file_cleanup
                        WHERE claimed_by = ?
                        ORDER BY attempts, updated_at, cleanup_id
                        """,
                this::map,
                claimToken);
    }

    @Override
    public boolean markFailed(long id, String claimToken, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE pending_file_cleanup
                SET attempts = attempts + 1, last_error = ?, claimed_by = NULL,
                    claimed_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE cleanup_id = ? AND claimed_by = ?
                """, errorMessage, id, claimToken) == 1;
    }

    @Override
    public boolean complete(long id, String claimToken) {
        return jdbcTemplate.update("""
                DELETE FROM pending_file_cleanup
                WHERE cleanup_id = ? AND claimed_by = ?
                """, id, claimToken) == 1;
    }

    private PendingFileCleanup map(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp claimedAt = resultSet.getTimestamp("claimed_at");
        return new PendingFileCleanup(
                resultSet.getLong("cleanup_id"),
                resultSet.getString("storage_type"),
                resultSet.getString("relative_path"),
                resultSet.getInt("attempts"),
                resultSet.getString("last_error"),
                resultSet.getString("claimed_by"),
                claimedAt == null ? null : claimedAt.toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
