-- RFC 0011 Part B: Anonymous Free-Tier Scan — session and device tables
-- Privacy: no client IP, no MAC address stored anywhere in these tables.

CREATE TYPE anon_session_status AS ENUM (
    'ACTIVE', 'SCAN_COMPLETE', 'CLAIMED', 'EXPIRED', 'DELETED'
);

CREATE TABLE anon_scan_sessions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scan_token_hash   CHAR(64) NOT NULL UNIQUE,
    view_token_hash   CHAR(64) NOT NULL UNIQUE,
    status            anon_session_status NOT NULL DEFAULT 'ACTIVE',
    scan_expires_at   TIMESTAMPTZ NOT NULL,
    view_expires_at   TIMESTAMPTZ NOT NULL,
    claimed_by_org_id UUID REFERENCES organizations(id),
    claimed_at        TIMESTAMPTZ,
    subnet_count      INTEGER NOT NULL DEFAULT 0,
    device_count      INTEGER NOT NULL DEFAULT 0,
    tls_found_count   INTEGER NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
    -- No client_ip column — not stored (privacy decision)
);

CREATE INDEX idx_anon_sessions_view_token ON anon_scan_sessions(view_token_hash);
CREATE INDEX idx_anon_sessions_status     ON anon_scan_sessions(status)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_anon_sessions_expires    ON anon_scan_sessions(view_expires_at);

CREATE TABLE anon_discovered_subnets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID NOT NULL REFERENCES anon_scan_sessions(id) ON DELETE CASCADE,
    cidr        VARCHAR(43) NOT NULL,
    iface_name  VARCHAR(64),
    source      VARCHAR(16) NOT NULL DEFAULT 'LOCAL_NIC',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, cidr)
);

CREATE INDEX idx_anon_subnets_session ON anon_discovered_subnets(session_id);

CREATE TABLE anon_discovered_devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES anon_scan_sessions(id) ON DELETE CASCADE,
    subnet_id       UUID NOT NULL REFERENCES anon_discovered_subnets(id),
    device_class    device_class NOT NULL DEFAULT 'UNKNOWN',
    open_port_count INTEGER NOT NULL DEFAULT 0,
    tls_port_count  INTEGER NOT NULL DEFAULT 0,
    open_ports      INTEGER[] NOT NULL DEFAULT '{}',
    banners         JSONB,
    tls_subjects    TEXT[],
    tls_expiry_min  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
    -- No ip column — not stored (privacy decision)
);

CREATE INDEX idx_anon_devices_session ON anon_discovered_devices(session_id);
CREATE INDEX idx_anon_devices_subnet  ON anon_discovered_devices(subnet_id);
