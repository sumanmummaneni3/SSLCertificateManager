-- ============================================================
-- V3: Email verification and password reset tokens
-- ============================================================

ALTER TABLE auth_users
    ADD COLUMN email_verification_token      VARCHAR(256),
    ADD COLUMN email_verification_expires_at TIMESTAMPTZ,
    ADD COLUMN password_reset_token          VARCHAR(256),
    ADD COLUMN password_reset_expires_at     TIMESTAMPTZ;

CREATE INDEX idx_auth_users_verification_token
    ON auth_users(email_verification_token)
    WHERE email_verification_token IS NOT NULL;

CREATE INDEX idx_auth_users_reset_token
    ON auth_users(password_reset_token)
    WHERE password_reset_token IS NOT NULL;
