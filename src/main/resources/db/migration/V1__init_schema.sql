CREATE TABLE `user` (
    user_id INT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(100) NOT NULL,
    permission BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    CONSTRAINT uk_user_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE voice (
    voice_id INT NOT NULL AUTO_INCREMENT,
    voice_name VARCHAR(128) NOT NULL,
    application_scene VARCHAR(255) NULL,
    file_path VARCHAR(255) NOT NULL COMMENT 'Server-generated relative storage key',
    mime_type VARCHAR(100) NULL,
    public_visible BOOLEAN NOT NULL DEFAULT FALSE,
    owner_username VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (voice_id),
    INDEX idx_voice_owner_username (owner_username),
    INDEX idx_voice_public_visible (public_visible)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE speaking_history (
    history_id BIGINT NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(64) NULL,
    username VARCHAR(64) NOT NULL,
    reference_text TEXT NOT NULL,
    user_text TEXT NOT NULL,
    fluency_score DECIMAL(6,2) NOT NULL,
    pronunciation_score DECIMAL(6,2) NOT NULL,
    accuracy_score DECIMAL(6,2) NOT NULL,
    correctness_rate DECIMAL(6,2) NOT NULL,
    mistakes TEXT NULL,
    feedback TEXT NULL,
    mode VARCHAR(32) NULL,
    language VARCHAR(16) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (history_id),
    INDEX idx_speaking_history_username_created (username, created_at),
    INDEX idx_speaking_history_session_user_created (session_id, username, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
