ALTER TABLE async_task
    ADD COLUMN payload_json MEDIUMTEXT NULL AFTER task_type,
    ADD COLUMN attempts INT UNSIGNED NOT NULL DEFAULT 0 AFTER progress,
    ADD COLUMN max_attempts INT UNSIGNED NOT NULL DEFAULT 1 AFTER attempts,
    ADD COLUMN available_at TIMESTAMP(6) NULL AFTER max_attempts,
    ADD COLUMN heartbeat_at TIMESTAMP(6) NULL AFTER started_at,
    ADD COLUMN worker_id VARCHAR(128) NULL AFTER heartbeat_at,
    ADD COLUMN version BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER worker_id,
    ADD COLUMN error_code VARCHAR(64) NULL AFTER result_data;

UPDATE async_task
SET payload_json = '{}',
    available_at = created_at
WHERE payload_json IS NULL OR available_at IS NULL;

ALTER TABLE async_task
    MODIFY COLUMN payload_json MEDIUMTEXT NOT NULL,
    MODIFY COLUMN available_at TIMESTAMP(6) NOT NULL,
    DROP INDEX idx_async_task_status_created,
    ADD INDEX idx_async_task_status_available_created (
        status, available_at, created_at
    ),
    ADD INDEX idx_async_task_worker_status (worker_id, status);
