-- ============================================================
-- V33 — Per-target / org-default notification settings (RFC 0008 §3)
-- ============================================================
-- Provides a three-tier resolution chain for expiry-alert thresholds:
--   per-target override → org default → app.yml fallback
--
-- Two partial unique indexes enforce the cardinality rules without the
-- pitfall of UNIQUE(org_id, target_id) treating two NULLs as distinct:
--   • Exactly one org-default row per org  (target_id IS NULL)
--   • At most one override per target      (target_id IS NOT NULL)
--
-- Additive only — no drops or type changes.  ddl-auto: validate stays green.
-- ============================================================

CREATE TABLE notification_settings (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    target_id     UUID            NULL REFERENCES targets(id)       ON DELETE CASCADE,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    warning_days  INT         NOT NULL DEFAULT 30,
    critical_days INT         NOT NULL DEFAULT 7,
    dedup_hours   INT         NOT NULL DEFAULT 23,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ns_thresholds CHECK (
        critical_days > 0
        AND warning_days > critical_days
        AND dedup_hours >= 1
    )
);

-- One org-default (target_id IS NULL) per org.
CREATE UNIQUE INDEX uq_notification_settings_org_default
    ON notification_settings(org_id) WHERE target_id IS NULL;

-- At most one per-target override.
CREATE UNIQUE INDEX uq_notification_settings_target
    ON notification_settings(target_id) WHERE target_id IS NOT NULL;

CREATE INDEX idx_notification_settings_org_id ON notification_settings(org_id);

-- Reuse the existing update_updated_at() trigger function (created in V1).
CREATE TRIGGER trg_notification_settings_updated_at
    BEFORE UPDATE ON notification_settings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
