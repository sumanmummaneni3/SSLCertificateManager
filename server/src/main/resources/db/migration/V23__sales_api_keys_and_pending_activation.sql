-- ============================================================
-- V23 — Sales API keys + PENDING_ACTIVATION subscription status
-- ============================================================

-- 1. Add PENDING_ACTIVATION to the subscription_status enum.
--    PostgreSQL 12+ allows ALTER TYPE ADD VALUE inside a transaction.
ALTER TYPE subscription_status ADD VALUE IF NOT EXISTS 'PENDING_ACTIVATION';

-- 2. Create sales_key_status enum for the API key lifecycle.
CREATE TYPE sales_key_status AS ENUM ('ACTIVE', 'REVOKED');

-- 3. Sales API keys table — used by the external Sales app for M2M auth.
CREATE TABLE sales_api_keys (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    label        VARCHAR(100) NOT NULL UNIQUE,
    key_hash     VARCHAR(255) NOT NULL,
    status       sales_key_status NOT NULL DEFAULT 'ACTIVE',
    created_by   UUID         REFERENCES users(id) ON DELETE SET NULL,
    last_used_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sales_api_keys_status ON sales_api_keys(status);

CREATE TRIGGER set_updated_at_sales_api_keys
    BEFORE UPDATE ON sales_api_keys
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
