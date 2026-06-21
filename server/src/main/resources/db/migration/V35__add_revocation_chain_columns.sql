-- RFC 0009: Revocation and chain-validation columns on certificate_records,
--           plus policy columns on notification_settings.
--
-- All new columns are nullable or carry a DEFAULT so this is a safe online ADD
-- compatible with ddl-auto: validate.

ALTER TABLE certificate_records
    ADD COLUMN IF NOT EXISTS revocation_status            text,
    ADD COLUMN IF NOT EXISTS revocation_source            text,
    ADD COLUMN IF NOT EXISTS revocation_reason            text,
    ADD COLUMN IF NOT EXISTS revocation_reason_code       smallint,
    ADD COLUMN IF NOT EXISTS revoked_at                   timestamptz,
    ADD COLUMN IF NOT EXISTS revocation_checked_at        timestamptz,
    ADD COLUMN IF NOT EXISTS last_revocation_alert_sent_at timestamptz,
    ADD COLUMN IF NOT EXISTS chain_trusted                boolean,
    ADD COLUMN IF NOT EXISTS chain_validation_error       text,
    ADD COLUMN IF NOT EXISTS revocation_deep_check        boolean NOT NULL DEFAULT false;

ALTER TABLE notification_settings
    ADD COLUMN IF NOT EXISTS revocation_check_enabled     boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS revocation_fail_mode         text    NOT NULL DEFAULT 'SOFT',
    ADD COLUMN IF NOT EXISTS alert_on_untrusted_chain     boolean NOT NULL DEFAULT false;

-- Partial index to efficiently query certs eligible for revocation recheck
-- (used by RevocationRecheckScheduler): non-expired, non-unreachable certs
-- where a recheck is needed.
CREATE INDEX IF NOT EXISTS idx_cert_records_revocation_recheck
    ON certificate_records (revocation_checked_at)
    WHERE status NOT IN ('EXPIRED', 'UNREACHABLE');
