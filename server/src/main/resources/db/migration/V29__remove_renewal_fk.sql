-- V29 — Drop FK from agent_jobs.renewal_id to certificate_renewal_requests
-- renewal_id becomes a soft UUID reference (no FK constraint) to the renewal service DB
ALTER TABLE agent_jobs DROP CONSTRAINT IF EXISTS fk_agent_jobs_renewal;
