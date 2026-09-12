-- A task created by an older application instance cannot be resumed safely by the
-- current in-process executor. Terminate legacy active rows once during migration;
-- stale-task recovery and durable worker claiming are introduced in Phase 5.
UPDATE async_task
SET status = 'FAILED',
    error_message = '任务因原单实例执行器升级而中断',
    finished_at = COALESCE(finished_at, CURRENT_TIMESTAMP)
WHERE status IN ('PENDING', 'RUNNING');

ALTER TABLE async_task
    ADD COLUMN active_deduplication_key VARCHAR(255)
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN ('PENDING', 'RUNNING') THEN deduplication_key
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE INDEX uq_async_task_active_deduplication (
        owner_username, task_type, active_deduplication_key
    );

CREATE TABLE async_task_user_slot (
    owner_username VARCHAR(64) NOT NULL,
    slot_number INT UNSIGNED NOT NULL,
    task_id CHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_username, slot_number),
    UNIQUE INDEX uq_async_task_user_slot_task (task_id),
    CONSTRAINT fk_async_task_user_slot_task
        FOREIGN KEY (task_id) REFERENCES async_task (task_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
