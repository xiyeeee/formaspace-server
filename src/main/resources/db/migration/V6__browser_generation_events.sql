CREATE TABLE fs_generation_browser_state (
    task_id BIGINT NOT NULL PRIMARY KEY,
    sequence BIGINT NOT NULL DEFAULT 0,
    snapshot_json TEXT NOT NULL,
    CONSTRAINT fk_browser_state_task FOREIGN KEY(task_id) REFERENCES fs_generation_task(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE fs_generation_browser_dedup (
    task_id BIGINT NOT NULL,
    source_key VARCHAR(160) NOT NULL,
    PRIMARY KEY(task_id,source_key),
    CONSTRAINT fk_browser_dedup_task FOREIGN KEY(task_id) REFERENCES fs_generation_task(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE fs_generation_preview ADD COLUMN decode_seconds DOUBLE NULL;
