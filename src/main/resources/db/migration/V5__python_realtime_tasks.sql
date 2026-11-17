ALTER TABLE fs_generation_task
    ADD COLUMN inference_task_id CHAR(36) NULL,
    ADD COLUMN inference_service VARCHAR(64) NULL,
    ADD COLUMN python_parameters TEXT NULL,
    ADD COLUMN last_python_sequence BIGINT NOT NULL DEFAULT -1,
    ADD COLUMN completed_steps INT NOT NULL DEFAULT 0,
    ADD COLUMN total_steps INT NOT NULL DEFAULT 0,
    ADD COLUMN python_preview_version INT NOT NULL DEFAULT 0,
    ADD COLUMN stored_preview_version INT NOT NULL DEFAULT 0,
    ADD COLUMN connection_state VARCHAR(24) NULL,
    ADD COLUMN submission_state VARCHAR(24) NULL,
    ADD COLUMN submission_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN preview_error_code VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_task_inference_uuid (inference_task_id);

CREATE TABLE fs_generation_preview (
    task_id BIGINT NOT NULL,
    version INT NOT NULL,
    source_step INT NOT NULL,
    point_count INT NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (task_id, version),
    CONSTRAINT fk_preview_task FOREIGN KEY (task_id) REFERENCES fs_generation_task(id) ON DELETE RESTRICT,
    CONSTRAINT ck_preview_metadata CHECK (version > 0 AND source_step > 0 AND point_count BETWEEN 1 AND 5000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
