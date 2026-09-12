CREATE TABLE stored_object_metadata (
    object_key VARCHAR(512) NOT NULL,
    storage_provider VARCHAR(32) NOT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NULL,
    object_size BIGINT UNSIGNED NOT NULL,
    checksum_sha256 CHAR(64) NULL,
    owner_username VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (object_key),
    INDEX idx_stored_object_owner_created (owner_username, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Legacy courseware paths point at one node's local disk and cannot be copied by
-- a schema migration. Preserve the rows and scripts, but make the limitation explicit.
UPDATE courseware_project
SET status = 'FAILED',
    error_message = '原本地课件文件需重新上传后才能在对象存储模式使用'
WHERE source_path NOT LIKE 'courseware/%';

ALTER TABLE courseware_project
    MODIFY source_path VARCHAR(512) NOT NULL COMMENT 'Object storage key',
    MODIFY output_path VARCHAR(512) NOT NULL COMMENT 'Object storage key prefix',
    MODIFY audio_path VARCHAR(512) NULL COMMENT 'Object storage key',
    MODIFY video_path VARCHAR(512) NULL COMMENT 'Object storage key',
    MODIFY avatar_path VARCHAR(512) NULL COMMENT 'Object storage key';
