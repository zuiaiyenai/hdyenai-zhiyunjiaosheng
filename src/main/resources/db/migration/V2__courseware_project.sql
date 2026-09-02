CREATE TABLE courseware_project (
    project_id CHAR(36) NOT NULL,
    owner_username VARCHAR(64) NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    source_path VARCHAR(512) NOT NULL COMMENT 'Relative path under app.courseware-dir',
    output_path VARCHAR(512) NOT NULL COMMENT 'Relative project directory under app.courseware-dir',
    file_name VARCHAR(255) NOT NULL,
    script MEDIUMTEXT NULL,
    revision INT NOT NULL DEFAULT 0,
    voice VARCHAR(128) NOT NULL DEFAULT 'longxiao',
    speed DECIMAL(4,2) NOT NULL DEFAULT 1.00,
    pitch DECIMAL(4,2) NOT NULL DEFAULT 1.00,
    rhythm DECIMAL(4,2) NOT NULL DEFAULT 1.00,
    audio_path VARCHAR(512) NULL,
    video_path VARCHAR(512) NULL,
    avatar_path VARCHAR(512) NULL,
    error_message VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id),
    INDEX idx_courseware_project_owner_updated (owner_username, updated_at),
    INDEX idx_courseware_project_owner_status (owner_username, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE courseware_project_revision (
    project_id CHAR(36) NOT NULL,
    revision_number INT NOT NULL,
    instruction VARCHAR(1000) NOT NULL,
    script MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, revision_number),
    CONSTRAINT fk_courseware_revision_project
        FOREIGN KEY (project_id) REFERENCES courseware_project(project_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
