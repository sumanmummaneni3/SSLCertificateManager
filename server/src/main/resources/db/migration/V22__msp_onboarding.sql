-- ============================================================
-- V22 — MSP onboarding: soft-delete orgs + user onboarding state
-- ============================================================

-- 1. Soft-delete on organizations.
--    All read paths must filter `WHERE archived_at IS NULL`.
ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS archived_by UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS archive_reason VARCHAR(500);

-- Partial index — most queries hit live rows.
CREATE INDEX IF NOT EXISTS idx_orgs_active
    ON organizations(id) WHERE archived_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_orgs_parent_active
    ON organizations(parent_org_id) WHERE archived_at IS NULL;

-- 2. Onboarding tracking on users.
--    Set immediately for invited users (skip wizard) and after wizard completion.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS onboarding_completed_at TIMESTAMPTZ NULL;

-- Backfill: every user who already has at least one ACCEPTED org_member is "onboarded".
UPDATE users u
   SET onboarding_completed_at = u.created_at
 WHERE onboarding_completed_at IS NULL
   AND EXISTS (
       SELECT 1 FROM org_members m
        WHERE m.user_id = u.id
          AND m.invite_status = 'ACCEPTED'
   );

-- 3. Drop the self-service MSP toggle from UpdateOrgProfileRequest path.
--    The org_type column stays; only the WRITE path changes (see service guard).
--    No DDL change needed — guard is in code (@PreAuthorize).
