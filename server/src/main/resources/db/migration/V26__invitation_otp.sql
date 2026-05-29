-- ============================================================
-- V26 — Persistent invitation OTP store
--
-- Replaces the in-process ConcurrentHashMap used by InvitationService.
-- OTP values are stored BCrypt-hashed; plaintext is never persisted.
-- Rows are cleaned up by the scheduler in InvitationService once expired.
-- ============================================================

CREATE TABLE invitation_otp (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email      TEXT        NOT NULL,
    org_id     UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    otp_hash   TEXT        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resend_count INT       NOT NULL DEFAULT 0,
    attempts   INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_invitation_otp_email_org ON invitation_otp(email, org_id);
CREATE INDEX idx_invitation_otp_expires_at ON invitation_otp(expires_at);
