-- ============================================================
-- V25 — Revocation tracking on org_members + invitations;
--        token revocation store for session invalidation
-- ============================================================

-- Track who revoked a membership and when
ALTER TABLE org_members
    ADD COLUMN revoked_at          TIMESTAMPTZ,
    ADD COLUMN revoked_by_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN revoke_reason       TEXT;

-- Track who cancelled an invitation and when
ALTER TABLE invitations
    ADD COLUMN cancelled_at      TIMESTAMPTZ,
    ADD COLUMN cancelled_reason  TEXT;

-- Caffeine backing store: one active revocation per (user, org).
-- Revoked sessions are blocked until expires_at passes (≈ JWT TTL after revocation).
CREATE TABLE revoked_tokens (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id             UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    revoked_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    revoked_by_user_id UUID        REFERENCES users(id) ON DELETE SET NULL,
    reason             TEXT,
    UNIQUE (user_id, org_id)
);

CREATE INDEX idx_revoked_tokens_expires_at ON revoked_tokens(expires_at);
