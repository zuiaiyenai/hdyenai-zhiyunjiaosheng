ALTER TABLE courseware_project
    ADD COLUMN lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER updated_at;
