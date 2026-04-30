-- ============================================================
-- V1 — Core Schema
-- ============================================================

CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TYPE user_role AS ENUM ('ADMIN', 'MEMBER', 'VIEWER');
CREATE TYPE cert_status AS ENUM ('VALID', 'EXPIRING', 'EXPIRED', 'UNREACHABLE', 'UNKNOWN');
CREATE TYPE host_type AS ENUM ('DOMAIN', 'IP', 'HOSTNAME');
CREATE TYPE subscription_status AS ENUM ('ACTIVE', 'TRIAL', 'SUSPENDED', 'CANCELLED');

CREATE TABLE organizations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    slug       VARCHAR(100) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER update_organizations_updated_at
    BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE users (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email      VARCHAR(255) NOT NULL UNIQUE,
    name       VARCHAR(255),
    role       user_role NOT NULL DEFAULT 'MEMBER',
    google_sub VARCHAR(255) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_org_id ON users(org_id);
CREATE INDEX idx_users_email  ON users(email);

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE subscriptions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    max_targets INTEGER NOT NULL DEFAULT 10,
    status      subscription_status NOT NULL DEFAULT 'TRIAL',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER update_subscriptions_updated_at
    BEFORE UPDATE ON subscriptions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE targets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    host        VARCHAR(255) NOT NULL,
    port        INTEGER NOT NULL DEFAULT 443,
    host_type   host_type NOT NULL DEFAULT 'DOMAIN',
    is_private  BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(255),
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    last_scanned_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_targets_org_id ON targets(org_id);

CREATE TRIGGER update_targets_updated_at
    BEFORE UPDATE ON targets
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE certificate_records (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id      UUID NOT NULL REFERENCES targets(id) ON DELETE CASCADE,
    org_id         UUID NOT NULL,
    common_name    VARCHAR(255) NOT NULL,
    issuer         TEXT NOT NULL,
    serial_number  VARCHAR(255) NOT NULL,
    expiry_date    TIMESTAMPTZ NOT NULL,
    not_before     TIMESTAMPTZ NOT NULL,
    public_cert_b64 TEXT,
    status         cert_status NOT NULL DEFAULT 'UNKNOWN',
    client_org_name VARCHAR(255),
    division_name   VARCHAR(255),
    scanned_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_certs_target_id  ON certificate_records(target_id);
CREATE INDEX idx_certs_org_id     ON certificate_records(org_id);
CREATE INDEX idx_certs_expiry     ON certificate_records(expiry_date);
CREATE INDEX idx_certs_status     ON certificate_records(status);

CREATE TRIGGER update_certificate_records_updated_at
    BEFORE UPDATE ON certificate_records
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
