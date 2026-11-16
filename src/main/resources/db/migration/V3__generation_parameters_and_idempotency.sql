ALTER TABLE fs_generation_task
    ADD COLUMN idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN requested_parameters JSON NULL,
    ADD COLUMN resolved_parameters JSON NULL,
    ADD COLUMN parameter_schema_version INT NULL,
    ADD COLUMN model_id VARCHAR(128) NULL,
    ADD COLUMN profile_id VARCHAR(128) NULL,
    ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD UNIQUE KEY uk_task_user_idempotency (user_id, idempotency_key),
    ADD KEY idx_task_mock_schedule (simulated, status, updated_at, id);

ALTER TABLE fs_model_asset ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT FALSE;
