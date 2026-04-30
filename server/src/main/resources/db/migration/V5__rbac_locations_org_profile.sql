-- ============================================================
-- V5 — RBAC, Locations, Org Profile
-- ============================================================

-- ── New enums ─────────────────────────────────────────────────────────────
CREATE TYPE org_type          AS ENUM ('SINGLE', 'MSP');
CREATE TYPE org_member_role   AS ENUM ('ADMIN', 'ENGINEER', 'VIEWER');
CREATE TYPE invite_status     AS ENUM ('PENDING', 'ACCEPTED', 'REVOKED');
CREATE TYPE location_provider AS ENUM ('AWS', 'AZURE', 'GCP', 'COLOCATION', 'ON_PREM');

-- ── Extend organizations ──────────────────────────────────────────────────
ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS org_type       org_type    NOT NULL DEFAULT 'SINGLE',
    ADD COLUMN IF NOT EXISTS parent_org_id  UUID        REFERENCES organizations(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS address_line1  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_line2  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS city           VARCHAR(100),
    ADD COLUMN IF NOT EXISTS state_province VARCHAR(100),
    ADD COLUMN IF NOT EXISTS postal_code    VARCHAR(20),
    ADD COLUMN IF NOT EXISTS country        VARCHAR(100),
    ADD COLUMN IF NOT EXISTS phone          VARCHAR(50),
    ADD COLUMN IF NOT EXISTS contact_email  VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_orgs_parent ON organizations(parent_org_id);

-- ── locations ─────────────────────────────────────────────────────────────
CREATE TABLE locations (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name          VARCHAR(100) NOT NULL,
    provider      location_provider NOT NULL,
    geo_region    VARCHAR(100),
    cloud_region  VARCHAR(100),
    address       VARCHAR(500),
    custom_fields JSONB NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_locations_org_id ON locations(org_id);

CREATE TRIGGER update_locations_updated_at
    BEFORE UPDATE ON locations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ── Extend targets with location FK ──────────────────────────────────────
ALTER TABLE targets
    ADD COLUMN IF NOT EXISTS location_id UUID REFERENCES locations(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_targets_location_id ON targets(location_id);

-- ── org_members ───────────────────────────────────────────────────────────
CREATE TABLE org_members (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role          org_member_role NOT NULL DEFAULT 'VIEWER',
    invited_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    invite_status invite_status NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, user_id)
);

CREATE INDEX idx_org_members_org_id  ON org_members(org_id);
CREATE INDEX idx_org_members_user_id ON org_members(user_id);

CREATE TRIGGER update_org_members_updated_at
    BEFORE UPDATE ON org_members
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ── invitations ───────────────────────────────────────────────────────────
CREATE TABLE invitations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email      VARCHAR(255) NOT NULL,
    role       org_member_role NOT NULL,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    invited_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invitations_org_id ON invitations(org_id);
CREATE INDEX idx_invitations_email  ON invitations(email);
