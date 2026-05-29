-- V31 — Add preferred CA provider to targets
-- VARCHAR(32), not a PG enum, to avoid duplicating the enum type across the separate renewal service DB.
ALTER TABLE targets ADD COLUMN IF NOT EXISTS preferred_ca_provider VARCHAR(32);
