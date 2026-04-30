-- ============================================================
-- V4 — Platform Admin role + certificate quota on subscriptions
-- ============================================================

-- 1. Extend the user_role enum with PLATFORM_ADMIN
--    PostgreSQL requires a two-step approach: add value, then use it.
ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'PLATFORM_ADMIN';

-- 2. Rename max_targets → max_certificate_quota on subscriptions
--    so it is clear this governs scanned certificates, not target rows.
ALTER TABLE subscriptions
    RENAME COLUMN max_targets TO max_certificate_quota;

-- 3. Ensure existing rows have the default of 10 (they already do,
--    but this makes the intent explicit for new rows too).
ALTER TABLE subscriptions
    ALTER COLUMN max_certificate_quota SET DEFAULT 10;
