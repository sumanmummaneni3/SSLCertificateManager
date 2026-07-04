-- ============================================================
-- V41 — Scanner Pool (RFC 0013)
-- Extends agents + agent_scan_jobs to support the public-target
-- pool claimed by platform-operated scanner agents.
-- ============================================================

-- 1. Agent type: CUSTOMER (existing) vs platform-operated PLATFORM_SCANNER
CREATE TYPE agent_type AS ENUM ('CUSTOMER', 'PLATFORM_SCANNER');
ALTER TABLE agents ADD COLUMN agent_type agent_type NOT NULL DEFAULT 'CUSTOMER';

-- 2. Pool jobs have no pre-assigned agent; agent_id is stamped on claim.
ALTER TABLE agent_scan_jobs ALTER COLUMN agent_id DROP NOT NULL;

-- 3. Job kind: AGENT_PINNED (today, agent pre-assigned) or PUBLIC_POOL (any scanner claims it).
ALTER TABLE agent_scan_jobs
    ADD COLUMN job_kind VARCHAR(20) NOT NULL DEFAULT 'AGENT_PINNED';

-- 4. Attempt counter for the ERROR->retry->FAILED progression.
--    Reuses the existing error_msg column for the error text (no new column).
ALTER TABLE agent_scan_jobs
    ADD COLUMN attempts INT NOT NULL DEFAULT 0;

-- 5. Partial index for the pool-pending claim query: fast lookup of unclaimed pool jobs
--    ordered by creation time.
CREATE INDEX idx_scan_jobs_pool_pending
    ON agent_scan_jobs (created_at)
    WHERE job_kind = 'PUBLIC_POOL' AND status = 'PENDING';

-- 6. CHECK constraint: AGENT_PINNED jobs must have an agent; PUBLIC_POOL start without one.
--    (agent_id is stamped to the claimer on claim for both audit and result verification.)
ALTER TABLE agent_scan_jobs ADD CONSTRAINT chk_job_kind_agent_id
    CHECK (
        (job_kind = 'AGENT_PINNED' AND agent_id IS NOT NULL)
        OR
        (job_kind = 'PUBLIC_POOL')   -- agent_id may be NULL (unclaimed) or NOT NULL (claimed)
    );

-- 7. Seed the reserved platform organization.
--    All platform-operated scanner agents belong to this org so they are invisible
--    to customer org queries (org-scoped list endpoints filter by the caller's org_id).
--    Fixed well-known UUID -- referenced in platform-admin provisioning code.
INSERT INTO organizations (id, name, slug)
VALUES ('00000000-0000-4000-8000-000000000000', 'CertGuard Platform', 'certguard-platform')
ON CONFLICT (id) DO NOTHING;
