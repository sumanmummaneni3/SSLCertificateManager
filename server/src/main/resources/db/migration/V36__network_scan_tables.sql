-- RFC 0011 Part A: Network Discovery & TLS Sweep — authenticated org-scoped tables

CREATE TYPE network_scan_status AS ENUM (
    'PENDING', 'IN_PROGRESS', 'COMPLETE', 'FAILED', 'CANCELLED'
);

CREATE TYPE port_scan_profile AS ENUM (
    'COMMON_TLS', 'EXTENDED', 'FULL', 'CUSTOM'
);

CREATE TYPE endpoint_port_state AS ENUM (
    'OPEN_TLS', 'OPEN_NO_TLS', 'CLOSED_OR_FILTERED'
);

CREATE TYPE device_class AS ENUM (
    'ROUTER', 'SWITCH', 'SERVER', 'WORKSTATION', 'UNKNOWN'
);

CREATE TABLE network_scans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    agent_id        UUID NOT NULL REFERENCES agents(id),
    cidr            VARCHAR(43) NOT NULL,
    port_profile    port_scan_profile NOT NULL,
    custom_ports    INTEGER[],
    status          network_scan_status NOT NULL DEFAULT 'PENDING',
    hosts_total     INTEGER,
    hosts_scanned   INTEGER NOT NULL DEFAULT 0,
    open_port_count INTEGER NOT NULL DEFAULT 0,
    tls_found_count INTEGER NOT NULL DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_network_scans_org    ON network_scans(org_id);
CREATE INDEX idx_network_scans_agent  ON network_scans(agent_id);
CREATE INDEX idx_network_scans_status ON network_scans(status)
    WHERE status IN ('PENDING','IN_PROGRESS');

CREATE TABLE discovered_endpoints (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    network_scan_id UUID NOT NULL REFERENCES network_scans(id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    ip              INET NOT NULL,
    port            INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535),
    state           endpoint_port_state NOT NULL,
    device_class    device_class NOT NULL DEFAULT 'UNKNOWN',
    banners         JSONB,
    cert_record_id  UUID REFERENCES certificate_records(id),
    tls_subject_cn  VARCHAR(255),
    tls_not_after   TIMESTAMPTZ,
    tls_cert_status cert_status,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (network_scan_id, ip, port)
);

CREATE INDEX idx_disc_endpoints_scan ON discovered_endpoints(network_scan_id);
CREATE INDEX idx_disc_endpoints_org  ON discovered_endpoints(org_id);
CREATE INDEX idx_disc_endpoints_tls  ON discovered_endpoints(network_scan_id)
    WHERE state = 'OPEN_TLS';
