ALTER TABLE voice
    DROP INDEX idx_voice_owner_username,
    DROP INDEX idx_voice_public_visible,
    ADD INDEX idx_voice_owner_visibility_created (
        owner_username, public_visible, created_at, voice_id
    ),
    ADD INDEX idx_voice_visibility_created (
        public_visible, created_at, voice_id
    );

ALTER TABLE async_task
    ADD INDEX idx_async_task_status_heartbeat (
        status, heartbeat_at
    );

ALTER TABLE stored_object_metadata
    ADD INDEX idx_stored_object_owner_key (
        owner_username, object_key
    );
