-- V8: Org-level notification channel configuration
-- Provides a fallback when a target has no per-target notification_channels JSONB.

CREATE TABLE org_notification_channels (
    id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    org_id       UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    channel_type TEXT        NOT NULL,
    config       JSONB,
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_org_notification_channels_org_id ON org_notification_channels(org_id);

CREATE TRIGGER trg_org_notification_channels_updated_at
    BEFORE UPDATE ON org_notification_channels
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
