CREATE TABLE pending_file_cleanup (
    cleanup_id BIGINT NOT NULL AUTO_INCREMENT,
    storage_type VARCHAR(32) NOT NULL,
    relative_path VARCHAR(512) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (cleanup_id),
    INDEX idx_pending_file_cleanup_retry (attempts, updated_at, cleanup_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
