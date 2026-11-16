-- MySQL 8.0.16+; DATETIME(6) values are UTC, written through UtcInstantTypeHandler.
-- Physical deletion only. Ownership and deletion order are checked by future services.
CREATE TABLE fs_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    password_hash VARCHAR(255) NOT NULL COMMENT 'Salted adaptive password hash with algorithm identifier; never plaintext',
    nickname VARCHAR(100) NOT NULL,
    avatar_url VARCHAR(2048) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_email (email),
    UNIQUE KEY uk_user_username (username),
    CONSTRAINT chk_user_email_normalized CHECK (BINARY email = BINARY LOWER(TRIM(email))),
    CONSTRAINT chk_user_username_normalized CHECK (
        BINARY username = BINARY LOWER(username) AND REGEXP_LIKE(username, '^[a-z0-9_]{3,32}$', 'c')),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fs_email_verification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(24) NOT NULL,
    code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'HMAC-SHA256(email + purpose + code), never plaintext',
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    sent_at DATETIME(6) NOT NULL COMMENT 'Last successful issue time; cooldown anchor, separate from audit creation',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_verification_slot (email, purpose),
    KEY idx_verification_expiry (expires_at),
    CONSTRAINT chk_verification_email_normalized CHECK (BINARY email = BINARY LOWER(TRIM(email))),
    CONSTRAINT chk_verification_purpose CHECK (purpose IN ('REGISTER', 'RESET_PASSWORD')),
    CONSTRAINT chk_verification_attempts CHECK (failed_attempts BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fs_auth_rate_limit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    bucket_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'HMAC of operation and email or IP',
    window_start BIGINT NOT NULL COMMENT 'UTC epoch seconds',
    attempts INT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rate_bucket_window (bucket_key, window_start),
    KEY idx_rate_expiry (expires_at),
    CONSTRAINT chk_rate_attempts CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fs_file (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(1024) NOT NULL COMMENT 'Permanent storage identifier, never a temporary signed URL',
    content_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT NOT NULL,
    purpose VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    KEY idx_file_user_created (user_id, created_at, id),
    CONSTRAINT chk_file_size CHECK (size_bytes >= 0),
    CONSTRAINT chk_file_purpose CHECK (purpose IN ('SOURCE_IMAGE', 'MODEL', 'THUMBNAIL')),
    CONSTRAINT fk_file_user FOREIGN KEY (user_id) REFERENCES fs_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fs_generation_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    source_file_id BIGINT NOT NULL,
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Stable provider code; chosen in a later iteration',
    provider_task_id VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    progress INT NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(512) NULL COMMENT 'Sanitized user-readable message, never raw provider response',
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    KEY idx_task_user_created (user_id, created_at, id),
    KEY idx_task_user_status_created (user_id, status, created_at, id),
    KEY idx_task_schedule (status, updated_at, id),
    UNIQUE KEY uk_task_provider (provider, provider_task_id),
    KEY idx_task_source (source_file_id),
    CONSTRAINT chk_task_status CHECK (status IN ('PENDING', 'SUBMITTING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_task_progress CHECK (progress IS NULL OR progress BETWEEN 0 AND 100),
    CONSTRAINT fk_task_user FOREIGN KEY (user_id) REFERENCES fs_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_task_source FOREIGN KEY (source_file_id) REFERENCES fs_file(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fs_model_asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    generation_task_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    model_file_id BIGINT NOT NULL,
    thumbnail_file_id BIGINT NULL,
    format VARCHAR(16) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_generation_task (generation_task_id),
    KEY idx_asset_user_created (user_id, created_at, id),
    KEY idx_asset_model_file (model_file_id),
    KEY idx_asset_thumbnail_file (thumbnail_file_id),
    CONSTRAINT chk_asset_size CHECK (size_bytes >= 0),
    CONSTRAINT fk_asset_user FOREIGN KEY (user_id) REFERENCES fs_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_asset_task FOREIGN KEY (generation_task_id) REFERENCES fs_generation_task(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_asset_model_file FOREIGN KEY (model_file_id) REFERENCES fs_file(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_asset_thumbnail_file FOREIGN KEY (thumbnail_file_id) REFERENCES fs_file(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
