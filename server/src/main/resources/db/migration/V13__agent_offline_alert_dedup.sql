-- V13: Add last_offline_alert_sent_at to agents for offline-alert deduplication.
--      The scheduler uses this to suppress repeated alerts within a 24-hour window.
ALTER TABLE agents ADD COLUMN IF NOT EXISTS last_offline_alert_sent_at TIMESTAMPTZ;
