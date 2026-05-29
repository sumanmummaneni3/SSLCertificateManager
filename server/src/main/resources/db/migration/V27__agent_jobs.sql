-- ============================================================
-- V27 — Generic Durable Agent Job Queue
-- ============================================================

CREATE TYPE agent_job_type   AS ENUM ('CERT_RENEW_CSR','CERT_DELIVERY');
CREATE TYPE agent_job_status AS ENUM ('PENDING','CLAIMED','COMPLETED','FAILED','CANCELLED');

CREATE TABLE agent_jobs (
    id            UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id      UUID             NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    org_id        UUID             NOT NULL,
    target_id     UUID             REFERENCES targets(id) ON DELETE SET NULL,
    renewal_id    UUID,                                     -- FK added in V28
    job_type      agent_job_type   NOT NULL,
    status        agent_job_status NOT NULL DEFAULT 'PENDING',
    payload       JSONB            NOT NULL DEFAULT '{}',
    dedup_key     VARCHAR(200),
    attempt_count INT              NOT NULL DEFAULT 0,
    error_code    VARCHAR(64),
    error_detail  TEXT,
    claimed_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_jobs_agent_status ON agent_jobs(agent_id, status);
CREATE INDEX idx_agent_jobs_org_id       ON agent_jobs(org_id);
CREATE INDEX idx_agent_jobs_status       ON agent_jobs(status);

CREATE UNIQUE INDEX uq_agent_jobs_dedup_active
    ON agent_jobs(dedup_key) WHERE status IN ('PENDING','CLAIMED');

CREATE TRIGGER update_agent_jobs_updated_at
    BEFORE UPDATE ON agent_jobs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
