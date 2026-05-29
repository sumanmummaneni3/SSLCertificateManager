-- ============================================================
-- V28 — Certificate Renewal Requests and Packages
-- ============================================================

CREATE TYPE renewal_status AS ENUM (
    'REQUESTED','CSR_PENDING','CSR_RECEIVED','CA_PENDING','CA_ISSUED',
    'STORED','DELIVERY_QUEUED','DELIVERED','FAILED','CANCELLED'
);
CREATE TYPE ca_provider_type AS ENUM ('NONE','LETS_ENCRYPT','DIGICERT','SECTIGO','INTERNAL');

CREATE TABLE certificate_renewal_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    certificate_id      UUID NOT NULL REFERENCES certificate_records(id) ON DELETE CASCADE,
    target_id           UUID NOT NULL REFERENCES targets(id) ON DELETE CASCADE,
    agent_id            UUID REFERENCES agents(id) ON DELETE SET NULL,
    status              renewal_status   NOT NULL DEFAULT 'REQUESTED',
    ca_provider         ca_provider_type NOT NULL DEFAULT 'NONE',
    ca_external_ref     VARCHAR(256),
    csr_pem             TEXT,
    requested_by        UUID NOT NULL,
    target_install_path VARCHAR(1024),
    package_id          UUID,
    csr_job_id          UUID REFERENCES agent_jobs(id) ON DELETE SET NULL,
    delivery_job_id     UUID REFERENCES agent_jobs(id) ON DELETE SET NULL,
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Public material only: leaf + chain, never a private key.
CREATE TABLE certificate_packages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    renewal_id          UUID NOT NULL REFERENCES certificate_renewal_requests(id) ON DELETE CASCADE,
    storage_path        VARCHAR(1024) NOT NULL,
    file_name           VARCHAR(256)  NOT NULL,
    content_type        VARCHAR(128)  NOT NULL DEFAULT 'application/x-pem-file',
    size_bytes          BIGINT        NOT NULL,
    checksum_sha256     VARCHAR(64)   NOT NULL,
    download_token_hash VARCHAR(64),
    downloaded_at       TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE certificate_renewal_requests
    ADD CONSTRAINT fk_renewal_package
    FOREIGN KEY (package_id) REFERENCES certificate_packages(id) ON DELETE SET NULL;

ALTER TABLE agent_jobs
    ADD CONSTRAINT fk_agent_jobs_renewal
    FOREIGN KEY (renewal_id) REFERENCES certificate_renewal_requests(id) ON DELETE SET NULL;

CREATE INDEX idx_renewal_org_id  ON certificate_renewal_requests(org_id);
CREATE INDEX idx_renewal_cert_id ON certificate_renewal_requests(certificate_id);
CREATE INDEX idx_renewal_status  ON certificate_renewal_requests(status);
CREATE INDEX idx_pkg_renewal_id  ON certificate_packages(renewal_id);
CREATE INDEX idx_pkg_org_id      ON certificate_packages(org_id);

CREATE TRIGGER update_certificate_renewal_requests_updated_at
    BEFORE UPDATE ON certificate_renewal_requests
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
