-- RFC 0012: Store subnets auto-discovered by the agent on registration and heartbeat.
-- Agents no longer require operator-typed CIDRs; allowed_cidrs remains for backward compat.

ALTER TABLE agents
    ADD COLUMN IF NOT EXISTS discovered_subnets jsonb NOT NULL DEFAULT '[]'::jsonb;

-- Ensure allowed_cidrs also has a default so rows inserted without it (e.g. bundle flow)
-- don't fail a NOT NULL constraint.
ALTER TABLE agents ALTER COLUMN allowed_cidrs SET DEFAULT '[]'::jsonb;
