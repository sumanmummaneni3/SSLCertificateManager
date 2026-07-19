-- ============================================================
-- V43 — Durable notification outbox (R19)
-- ============================================================
-- Fixes R19: ExpiryEvaluationService stamped last_alert_sent_at /
-- last_revocation_alert_sent_at synchronously (durable) but then dispatched
-- the actual email via an AFTER_COMMIT @Async call whose failures are only
-- logged (NotificationService.sendMimeEmail swallows all exceptions). On an
-- SMTP outage the alert is silently lost: expiry alerts are suppressed for
-- the dedup window (default 23h) and revocation alerts — which are
-- transition-gated and fire only once — are lost permanently.
--
-- The fix: ExpiryEvaluationService now inserts one outbox row per resolved
-- recipient address in the SAME transaction as the dedup stamp, so the
-- send-intent is as durable as the stamp. NotificationOutboxScheduler drains
-- PENDING rows on a fixed interval, retries with exponential backoff on
-- failure, and marks rows FAILED once the attempt cap is exhausted.
--
-- Additive only — no drops or type changes. ddl-auto: validate stays green.
-- ============================================================

CREATE TABLE notification_outbox (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    to_address       TEXT NOT NULL,
    subject          TEXT NOT NULL,
    template_name    TEXT NOT NULL,
    template_vars    JSONB NOT NULL DEFAULT '{}',
    status           TEXT NOT NULL DEFAULT 'PENDING',
    attempts         INT NOT NULL DEFAULT 0,
    last_error       TEXT,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ,
    CONSTRAINT chk_notification_outbox_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_notification_outbox_attempts
        CHECK (attempts >= 0)
);

-- Drain query: PENDING rows whose next_attempt_at is due, oldest first.
CREATE INDEX idx_notification_outbox_status_next_attempt
    ON notification_outbox(status, next_attempt_at);

CREATE INDEX idx_notification_outbox_org_id ON notification_outbox(org_id);

-- Reuse the existing update_updated_at() trigger function (created in V1).
CREATE TRIGGER trg_notification_outbox_updated_at
    BEFORE UPDATE ON notification_outbox
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
