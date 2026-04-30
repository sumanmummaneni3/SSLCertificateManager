-- ============================================================
-- V3 — Agent Support Schema
-- ============================================================

CREATE TYPE agent_status AS ENUM ('PENDING', 'ACTIVE', 'REVOKED', 'EXPIRED');
CREATE TYPE scan_job_status AS ENUM ('PENDING', 'CLAIMED', 'COMPLETED', 'FAILED');

CREATE TABLE agents (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name                    VARCHAR(100) NOT NULL,
    agent_key_hash          VARCHAR(255) NOT NULL,
    client_cert_fingerprint VARCHAR(255),
    client_cert_pem         TEXT,
    allowed_cidrs           JSONB NOT NULL DEFAULT '[]',
    max_targets             INTEGER NOT NULL DEFAULT 50,
    current_target_count    INTEGER NOT NULL DEFAULT 0,
    status                  agent_status NOT NULL DEFAULT 'PENDING',
    last_seen_at            TIMESTAMPTZ,
    registered_at           TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_agents_org_id ON agents(org_id);
CREATE INDEX idx_agents_status  ON agents(status);

CREATE TRIGGER update_agents_updated_at
    BEFORE UPDATE ON agents
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE agent_registration_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    agent_name VARCHAR(100) NOT NULL,
    used       BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reg_tokens_org_id ON agent_registration_tokens(org_id);

CREATE TABLE agent_scan_jobs (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id     UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    target_id    UUID NOT NULL REFERENCES targets(id) ON DELETE CASCADE,
    org_id       UUID NOT NULL,
    status       scan_job_status NOT NULL DEFAULT 'PENDING',
    result_type  VARCHAR(10),
    error_msg    VARCHAR(500),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_scan_jobs_agent_id  ON agent_scan_jobs(agent_id);
CREATE INDEX idx_scan_jobs_target_id ON agent_scan_jobs(target_id);
CREATE INDEX idx_scan_jobs_status    ON agent_scan_jobs(status);

CREATE TRIGGER update_scan_jobs_updated_at
    BEFORE UPDATE ON agent_scan_jobs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

ALTER TABLE targets
    ADD COLUMN IF NOT EXISTS agent_id UUID REFERENCES agents(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_targets_agent_id ON targets(agent_id);

ALTER TABLE certificate_records
    ADD COLUMN IF NOT EXISTS key_algorithm       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS key_size            INTEGER,
    ADD COLUMN IF NOT EXISTS signature_algorithm VARCHAR(50),
    ADD COLUMN IF NOT EXISTS subject_alt_names   JSONB,
    ADD COLUMN IF NOT EXISTS chain_depth         INTEGER,
    ADD COLUMN IF NOT EXISTS scanned_by_agent_id UUID REFERENCES agents(id) ON DELETE SET NULL;
