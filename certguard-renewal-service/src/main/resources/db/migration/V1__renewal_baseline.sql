-- ============================================================
-- V1 — Renewal Service Baseline Schema
-- All cross-service references are soft UUIDs (no FK to core DB).
-- ============================================================

-- ShedLock table for distributed scheduler deduplication
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- CA provider configurations (one per org, or platform default if org_id IS NULL)
CREATE TABLE ca_provider_configs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID,                    -- NULL = platform-wide default
    provider_type       VARCHAR(32)  NOT NULL,   -- LETS_ENCRYPT | DIGICERT | SECTIGO | INTERNAL | NOOP
    is_platform_default BOOLEAN      NOT NULL DEFAULT false,
    label               VARCHAR(128),
    credentials_enc     TEXT,                    -- AES-256-GCM encrypted JSON, key from RENEWAL_CREDENTIAL_KEK
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_ca_provider_platform_default
    ON ca_provider_configs (is_platform_default)
    WHERE is_platform_default = true;

-- Renewal requests (no FK to core tables — org_id/cert_id/target_id are soft refs)
CREATE TABLE certificate_renewal_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID         NOT NULL,
    cert_id             UUID         NOT NULL,
    target_id           UUID         NOT NULL,
    agent_id            UUID,
    status              VARCHAR(32)  NOT NULL DEFAULT 'REQUESTED',
    ca_provider         VARCHAR(32)  NOT NULL DEFAULT 'NOOP',
    ca_external_ref     VARCHAR(256),
    csr_pem             TEXT,
    requested_by        UUID         NOT NULL,
    target_install_path VARCHAR(1024),
    package_id          UUID,
    csr_job_id          UUID,                   -- soft ref to core agent_jobs.id
    delivery_job_id     UUID,                   -- soft ref to core agent_jobs.id
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_renewal_org_id  ON certificate_renewal_requests(org_id);
CREATE INDEX idx_renewal_cert_id ON certificate_renewal_requests(cert_id);
CREATE INDEX idx_renewal_status  ON certificate_renewal_requests(status);

-- Certificate packages (public material only — never a private key)
CREATE TABLE certificate_packages (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           UUID         NOT NULL,
    renewal_id       UUID         NOT NULL REFERENCES certificate_renewal_requests(id) ON DELETE CASCADE,
    storage_path     VARCHAR(1024) NOT NULL,
    file_name        VARCHAR(256)  NOT NULL,
    content_type     VARCHAR(128)  NOT NULL DEFAULT 'application/x-pem-file',
    size_bytes       BIGINT        NOT NULL,
    checksum_sha256  VARCHAR(64)   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

ALTER TABLE certificate_renewal_requests
    ADD CONSTRAINT fk_renewal_package
    FOREIGN KEY (package_id) REFERENCES certificate_packages(id) ON DELETE SET NULL;

CREATE INDEX idx_pkg_renewal_id ON certificate_packages(renewal_id);
CREATE INDEX idx_pkg_org_id     ON certificate_packages(org_id);

-- CA order tracking (for async CA flows like ACME)
CREATE TABLE ca_orders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    renewal_id      UUID         NOT NULL REFERENCES certificate_renewal_requests(id) ON DELETE CASCADE,
    provider_type   VARCHAR(32)  NOT NULL,
    external_ref    VARCHAR(256) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    poll_after      TIMESTAMPTZ,
    error_detail    TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ca_orders_renewal ON ca_orders(renewal_id);
CREATE INDEX idx_ca_orders_status  ON ca_orders(status);

-- Append-only certificate issuance history (outlives renewals, used for compliance/audit)
CREATE TABLE cert_issuance_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID         NOT NULL,
    cert_id         UUID         NOT NULL,
    target_id       UUID         NOT NULL,
    renewal_id      UUID,
    ca_provider     VARCHAR(32)  NOT NULL,
    common_name     VARCHAR(256),
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    serial_number   VARCHAR(128),
    issuer_dn       TEXT
);

CREATE INDEX idx_issuance_org     ON cert_issuance_history(org_id);
CREATE INDEX idx_issuance_cert    ON cert_issuance_history(cert_id);
CREATE INDEX idx_issuance_issued  ON cert_issuance_history(issued_at);

-- Shared update_updated_at trigger function
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_renewal_requests_updated_at
    BEFORE UPDATE ON certificate_renewal_requests
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_ca_provider_configs_updated_at
    BEFORE UPDATE ON ca_provider_configs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_ca_orders_updated_at
    BEFORE UPDATE ON ca_orders
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- Seed platform-default NOOP provider
INSERT INTO ca_provider_configs (provider_type, is_platform_default, label)
VALUES ('NOOP', true, 'Platform Default (No-op stub)');
