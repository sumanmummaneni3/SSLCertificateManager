-- ============================================================
-- V21 — Platform Admin audit trail
-- ============================================================

CREATE TABLE platform_admin_audit (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    acting_user_id    UUID NOT NULL REFERENCES users(id),
    acting_user_email VARCHAR(255) NOT NULL,
    target_org_id     UUID NOT NULL REFERENCES organizations(id),
    target_org_name   VARCHAR(255),
    http_method       VARCHAR(10) NOT NULL,
    request_path      VARCHAR(1024) NOT NULL,
    reason            TEXT,
    response_status   INT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_admin_audit_acting_user ON platform_admin_audit(acting_user_id);
CREATE INDEX idx_platform_admin_audit_target_org  ON platform_admin_audit(target_org_id);
CREATE INDEX idx_platform_admin_audit_created_at  ON platform_admin_audit(created_at);
