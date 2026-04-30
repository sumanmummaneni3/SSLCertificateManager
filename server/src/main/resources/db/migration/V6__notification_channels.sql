-- ============================================================
-- V6 — Notification channels on targets
-- ============================================================
ALTER TABLE targets
    ADD COLUMN IF NOT EXISTS notification_channels JSONB NOT NULL DEFAULT '{}';
