-- ============================================================
-- V10 — Agent Install Bundle Keys
-- ============================================================

CREATE TABLE agent_install_keys (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id                    UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    org_id                      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    kdf_salt                    BYTEA NOT NULL,
    kdf_memory_kib              INT NOT NULL,
    kdf_iterations              INT NOT NULL,
    kdf_parallelism             INT NOT NULL,
    install_key_hash            VARCHAR(255) NOT NULL,
    bundle_download_token_hash  VARCHAR(255) NOT NULL UNIQUE,
    sealed_payload              BYTEA NOT NULL,
    bundle_downloaded_at        TIMESTAMPTZ NULL,
    expires_at                  TIMESTAMPTZ NOT NULL,
    created_by                  UUID NULL REFERENCES users(id),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_agent_install_keys_agent_id ON agent_install_keys(agent_id);
CREATE INDEX idx_agent_install_keys_org_id   ON agent_install_keys(org_id);
CREATE INDEX idx_agent_install_keys_dl_token ON agent_install_keys(bundle_download_token_hash);

CREATE TRIGGER update_agent_install_keys_updated_at
    BEFORE UPDATE ON agent_install_keys
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
