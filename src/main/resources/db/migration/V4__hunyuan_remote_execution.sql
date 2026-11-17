ALTER TABLE fs_generation_task
    ADD COLUMN execution_mode VARCHAR(24) NULL COMMENT 'MOCK/HUNYUAN_LOCAL/LEGACY; explicit worker isolation',
    ADD COLUMN remote_reference TEXT NULL COMMENT 'Internal versioned Gradio event reference; never exposed in VO',
    ADD COLUMN remote_result_reference TEXT NULL,
    ADD COLUMN inference_stage VARCHAR(24) NULL,
    ADD COLUMN query_failures INT NOT NULL DEFAULT 0,
    ADD COLUMN next_poll_at DATETIME(6) NULL,
    ADD COLUMN deadline_at DATETIME(6) NULL,
    ADD KEY idx_task_execution_schedule (execution_mode, status, next_poll_at, id);

UPDATE fs_generation_task SET execution_mode=CASE WHEN simulated=TRUE THEN 'MOCK' WHEN provider='HUNYUAN_LOCAL' THEN 'HUNYUAN_LOCAL' ELSE 'LEGACY' END
    WHERE execution_mode IS NULL;
ALTER TABLE fs_generation_task MODIFY execution_mode VARCHAR(24) NOT NULL;
