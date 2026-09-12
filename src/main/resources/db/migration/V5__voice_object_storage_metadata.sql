ALTER TABLE voice
    ADD COLUMN object_key VARCHAR(512) NULL COMMENT 'Object storage key' AFTER file_path,
    ADD COLUMN storage_provider VARCHAR(32) NULL COMMENT 'Object storage provider' AFTER object_key,
    ADD COLUMN storage_bucket VARCHAR(255) NULL COMMENT 'Object storage bucket' AFTER storage_provider,
    ADD COLUMN file_size BIGINT NULL COMMENT 'Object size in bytes' AFTER mime_type,
    ADD COLUMN checksum_sha256 CHAR(64) NULL COMMENT 'SHA-256 checksum' AFTER file_size;

CREATE INDEX idx_voice_owner_storage ON voice (owner_username, storage_provider, storage_bucket);
