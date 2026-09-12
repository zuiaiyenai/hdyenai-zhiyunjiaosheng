CREATE TABLE IF NOT EXISTS async_task_admission_lock (
    lock_id TINYINT NOT NULL,
    PRIMARY KEY (lock_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO async_task_admission_lock (lock_id) VALUES (1);
