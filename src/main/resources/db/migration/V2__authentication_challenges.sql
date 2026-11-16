ALTER TABLE fs_user ADD COLUMN last_login_at DATETIME(6) NULL COMMENT 'UTC, last successful password login';

-- Old challenges have no challenge-bound HMAC; invalidate them instead of guessing a compatible value.
ALTER TABLE fs_email_verification
    ADD COLUMN challenge_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN send_status VARCHAR(16) NOT NULL DEFAULT 'FAILED',
    DROP CHECK chk_verification_attempts,
    ADD CONSTRAINT chk_verification_attempts CHECK (failed_attempts >= 0),
    ADD CONSTRAINT chk_verification_send_status CHECK (send_status IN ('PENDING', 'SENT', 'FAILED'));

ALTER TABLE fs_email_verification MODIFY sent_at DATETIME(6) NOT NULL
    COMMENT 'UTC reservation time; exact resend cooldown also applies to failed/pending delivery';
