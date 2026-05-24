-- ============================================================
-- V24 — Organisation-scoped audit trail
-- ============================================================

CREATE TABLE org_audit (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           UUID        NOT NULL REFERENCES organizations(id),
    actor_user_id    UUID        REFERENCES users(id) ON DELETE SET NULL,
    actor_email      VARCHAR(255) NOT NULL,
    action           VARCHAR(64)  NOT NULL,
    target_user_id   UUID        REFERENCES users(id) ON DELETE SET NULL,
    target_email     VARCHAR(255),
    reason           TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_org_audit_org_id     ON org_audit(org_id);
CREATE INDEX idx_org_audit_created_at ON org_audit(created_at);
