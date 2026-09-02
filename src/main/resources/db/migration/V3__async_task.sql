CREATE TABLE async_task (
    task_id CHAR(36) NOT NULL,
    owner_username VARCHAR(64) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    progress TINYINT UNSIGNED NOT NULL DEFAULT 0,
    result_data MEDIUMTEXT NULL,
    error_message VARCHAR(1000) NULL,
    deduplication_key VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    PRIMARY KEY (task_id),
    INDEX idx_async_task_owner_created (owner_username, created_at),
    INDEX idx_async_task_owner_status (owner_username, status),
    INDEX idx_async_task_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
