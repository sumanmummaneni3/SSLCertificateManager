-- V9: Add last_alert_sent_at to certificate_records for alert deduplication.
-- The expiry scheduler checks this column before dispatching a notification so
-- that already-expired (and near-expiry) certificates do not generate a new
-- alert on every daily run.  A NULL value means no alert has ever been sent.

ALTER TABLE certificate_records
    ADD COLUMN IF NOT EXISTS last_alert_sent_at TIMESTAMPTZ;

COMMENT ON COLUMN certificate_records.last_alert_sent_at
    IS 'UTC timestamp of the most recent expiry/expired alert dispatched for this certificate record. NULL = never alerted.';
