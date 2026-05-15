-- ============================================================
-- CertGuard Auth Service — initial schema
-- ============================================================

CREATE TABLE auth_users (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    provider_id       VARCHAR(32) NOT NULL,       -- google | microsoft | email
    provider_user_id  VARCHAR(256),               -- provider's sub/oid; NULL for email
    email             VARCHAR(320) NOT NULL,
    name              VARCHAR(256),
    password_hash     VARCHAR(256),               -- BCrypt; only for provider_id='email'
    access_token      VARCHAR(2048),
    refresh_token     VARCHAR(2048),
    token_expires_at  TIMESTAMPTZ,
    email_verified    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_auth_users PRIMARY KEY (id),
    CONSTRAINT uq_auth_users_email UNIQUE (email),
    CONSTRAINT uq_auth_users_provider UNIQUE (provider_id, provider_user_id)
);

CREATE TABLE auth_user_sessions (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL,
    session_token  TEXT         NOT NULL,
    provider       VARCHAR(32)  NOT NULL,
    refresh_token  VARCHAR(2048),
    expires_at     TIMESTAMPTZ  NOT NULL,
    last_used_at   TIMESTAMPTZ,
    client_ip      VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_auth_sessions PRIMARY KEY (id),
    CONSTRAINT uq_auth_sessions_token UNIQUE (session_token),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id)
        REFERENCES auth_users(id) ON DELETE CASCADE
);

CREATE INDEX idx_sessions_token     ON auth_user_sessions(session_token);
CREATE INDEX idx_sessions_user_id   ON auth_user_sessions(user_id);
CREATE INDEX idx_sessions_expires   ON auth_user_sessions(expires_at);

CREATE TABLE auth_rate_limit_buckets (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    bucket_key    VARCHAR(256) NOT NULL,
    attempt_count INT          NOT NULL DEFAULT 0,
    window_start  TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_rate_limit PRIMARY KEY (id),
    CONSTRAINT uq_rate_limit_key UNIQUE (bucket_key)
);

CREATE INDEX idx_rl_key ON auth_rate_limit_buckets(bucket_key);

-- auto-update updated_at on auth_users
CREATE OR REPLACE FUNCTION auth_set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_auth_users_updated_at
    BEFORE UPDATE ON auth_users
    FOR EACH ROW EXECUTE FUNCTION auth_set_updated_at();

CREATE TRIGGER trg_rate_limit_updated_at
    BEFORE UPDATE ON auth_rate_limit_buckets
    FOR EACH ROW EXECUTE FUNCTION auth_set_updated_at();
