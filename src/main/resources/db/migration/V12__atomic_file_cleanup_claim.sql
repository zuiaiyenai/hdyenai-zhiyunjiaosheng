ALTER TABLE pending_file_cleanup
    ADD COLUMN claimed_by VARCHAR(64) NULL AFTER last_error,
    ADD COLUMN claimed_at TIMESTAMP NULL AFTER claimed_by,
    ADD INDEX idx_pending_file_cleanup_claim
        (claimed_at, attempts, updated_at, cleanup_id);
