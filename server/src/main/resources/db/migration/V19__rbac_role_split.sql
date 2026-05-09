-- Step 1: add is_platform_admin flag (additive, safe to deploy before code)
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_platform_admin BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE users SET is_platform_admin = TRUE WHERE role = 'PLATFORM_ADMIN';

-- Step 2: backfill org_members for every user that lacks a row in their home org
INSERT INTO org_members (id, org_id, user_id, role, invite_status, created_at, updated_at)
SELECT gen_random_uuid(), u.org_id, u.id,
       CASE
           WHEN u.role = 'VIEWER' THEN 'VIEWER'::org_member_role
           WHEN u.role = 'MEMBER' THEN 'ENGINEER'::org_member_role
           ELSE 'ADMIN'::org_member_role
       END,
       'ACCEPTED'::invite_status,
       NOW(), NOW()
FROM users u
WHERE u.is_platform_admin = FALSE
  AND NOT EXISTS (
      SELECT 1 FROM org_members m
      WHERE m.user_id = u.id AND m.org_id = u.org_id
  );
