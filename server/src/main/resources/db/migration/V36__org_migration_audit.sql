-- V36__org_migration_audit.sql
-- Immutable audit table for MSP→MSP organisation migrations (RFC 0010).
-- No updated_at; append-only; DB-managed created_at.

CREATE TABLE org_migration_audit (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    direction                VARCHAR(16) NOT NULL,
    reverses_migration_id    UUID REFERENCES org_migration_audit(id),
    org_id                   UUID NOT NULL REFERENCES organizations(id),
    org_name                 VARCHAR(255),
    source_msp_org_id        UUID NOT NULL REFERENCES organizations(id),
    source_msp_name          VARCHAR(255),
    target_msp_org_id        UUID NOT NULL REFERENCES organizations(id),
    target_msp_name          VARCHAR(255),
    acting_user_id           UUID NOT NULL REFERENCES users(id),
    acting_user_email        VARCHAR(255) NOT NULL,
    reason                   TEXT NOT NULL,
    reference_ticket         VARCHAR(255),
    revoked_member_ids       JSONB,
    revoked_member_count     INT NOT NULL DEFAULT 0,
    in_flight_scan_job_count INT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_org_migration_audit_org_id     ON org_migration_audit(org_id);
CREATE INDEX idx_org_migration_audit_source_msp ON org_migration_audit(source_msp_org_id);
CREATE INDEX idx_org_migration_audit_target_msp ON org_migration_audit(target_msp_org_id);
CREATE INDEX idx_org_migration_audit_created_at ON org_migration_audit(created_at DESC);
