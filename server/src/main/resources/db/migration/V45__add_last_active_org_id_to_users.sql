-- RFC 0015 Phase 1: track the org a user was last active in so subsequent
-- logins can resolve the correct org instead of always re-minting the JWT
-- against the fixed home org (users.org_id).
ALTER TABLE users ADD COLUMN last_active_org_id UUID;
ALTER TABLE users ADD CONSTRAINT fk_users_last_active_org
    FOREIGN KEY (last_active_org_id) REFERENCES organizations(id);
