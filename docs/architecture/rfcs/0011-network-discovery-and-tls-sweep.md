# RFC 0011 — Network Discovery & TLS Sweep: Authenticated Port Scan, Anonymous Free-Tier Subnet Discovery, and Per-Subnet SSL Inventory

- **Status:** Draft (2026-06-28) — B2 scope and B5 privacy decisions ratified by product owner; legal review required before free-tier goes live
- **Authors:** CertGuard Architect
- **Relates to:** RFC 0008 (expiry notification convergence), RFC 0009 (chain validation and revocation), HLD §6 (agent pull-based model), LLD §4 (agent enrollment), GAPS.md (new gaps N16–N19)
- **Supersedes:** nothing — net-new capability

---

## 0. Grounding

Anchored against live source under `server/src/main/java/com/certguard/` and `agent/src/main/java/com/certguard/agent/`:

- **The agent's job model is single-endpoint, single-target.** `agent/model/ScanJob.java` carries `{jobId, targetId, host, port, lastKnownSerialHash, lastCertificateId}` — one job equals one pre-registered `Target`. There is no job-type discriminator and no host/port range concept.
- **The poll loop runs one fixed-delay thread.** `agent/http/PollLoop.java:46-104`: `tick()` → heartbeat → `GET /api/v1/agent/jobs` → cap at `maxTargets` → `newFixedThreadPool(scanThreads)` → `awaitTermination(5, MINUTES)` → submit results. A scan job exceeding 5 min stalls the pool. The server-side `AgentService.resetStaleClaimedJobs` (`service/AgentService.java:428-443`) reclaims any CLAIMED job after 10 min → double execution if a job outruns that threshold.
- **The scanner is direct-TLS only.** `agent/scanner/SslScanner.java:226-253` (`fetchChainWithProtocols`) opens a raw `Socket` and immediately wraps it in an `SSLSocket` — no STARTTLS protocol negotiation. FULL/DELTA logic is keyed on `targetId` via an in-memory serial cache (`serialCache`, lines 42, 60-74); discovered endpoints carry no `targetId`.
- **The result contract is one-cert-per-POST.** `agent/http/ServerApiClient.submitResult:135-173` → `service/AgentService.submitResult:188-236`: one certificate per request, HMAC over `targetId:scanType:serial:notAfterMs`, must resolve to an existing job + a `Target` assigned to this agent.
- **The data model is target-centric.** Every `CertificateRecord` is FK'd to a user-registered `Target`. There is no entity for a discovered host or a discovered open port — network scan results have nowhere to land. This is the gating data-model gap.
- **Auth is org-bound.** `AgentAuthFilter.java:56-88` requires `X-Agent-Id` (resolves to an `Agent` row), `X-Agent-Key` (BCrypt), and `status == ACTIVE`. `SecurityConfig.java:95` forces `authenticated()` on all `/api/v1/**`. An anonymous visitor has no org, no `Agent` row, no token.
- **CIDR scope enforcement exists** (`agents.allowed_cidrs`) and is validated at `AgentService.validateCidr:488-499`. This boundary does not exist for anonymous sessions and must be replaced by a server-side private-range allowlist.

---

## 1. Summary

This RFC introduces two related but independently deployable capabilities:

**Part A — Authenticated Network Scan:** Org-scoped agents gain a new `NETWORK_SCAN` job type that sweeps a CIDR range, identifies open ports via pure-Java TCP connect scan, probes each open port for TLS, and returns batched results to the server. Results land in new org-scoped tables (`network_scans`, `discovered_endpoints`) and are displayed in the existing portal UI under a new "Scan Network" flow. Scope is hard-enforced against the agent's registered `allowed_cidrs`.

**Part B — Anonymous Free-Tier Scan:** An unauthenticated visitor downloads a lightweight agent, which discovers directly-connected subnets via local NIC inspection, fingerprints live hosts (ports, banners, TLS certs), and pushes results to the server for display on a public read-only dashboard at `/scan/{viewToken}`. No account required. MAC addresses and client IPs are never persisted. The agent may perform exactly one scan per anonymous session; per-subnet drill-down is gated behind account creation and scan claim.

---

## 2. Goals / Non-Goals

### Goals

- TCP connect scan over configurable port profiles (common-TLS subset default; full 1–65535 optional)
- Direct-TLS probe on each open port; return the full chain (reuse RFC 0009 wire contract)
- Batch result submission to avoid one-cert-per-POST bottleneck
- New server-side data model for discovered hosts and endpoints (org-scoped and anon-scoped variants)
- Anonymous free-tier: NIC-based subnet discovery, host classification (port/banner/TLS), read-only public dashboard, claim flow
- Scope enforcement: authenticated → `allowed_cidrs`; anonymous → RFC1918 + agent-reported subnets only
- Data privacy: anonymous sessions store no MAC, no client IP, no PII

### Non-Goals

- STARTTLS support (SMTP/IMAP/LDAP etc.) — **DEFERRED** to a fast-follow
- nmap / masscan / SYN scan / raw-socket techniques — **DECIDED OUT**: require root, break the no-privilege agent model
- SNMP / SSH CLI / vendor REST router querying — **DECIDED OUT** for free tier; **DEFERRED** for post-signup opt-in
- LLDP passive capture — **DECIDED OUT**: requires promiscuous socket (root)
- TTL OS fingerprinting — **DECIDED OUT**: requires reading raw IP TTL (root)
- Per-subnet drill-down for anonymous sessions — **DECIDED OUT**: gated behind signup + claim
- In-place anonymous→org agent promotion — **DEFERRED**: v1 requires re-download after signup
- WebSocket / SSE progress streaming — **DEFERRED**: no sticky-session infra; UI polls

---

## 3. Part A — Authenticated Network Scan

### 3.1 New job type: `NETWORK_SCAN`

Add a job-type discriminator to the agent job model. The agent receives a `NETWORK_SCAN` job with:

```json
{
  "jobId": "<uuid>",
  "jobType": "NETWORK_SCAN",
  "networkScanId": "<uuid>",
  "cidr": "192.168.1.0/24",
  "portProfile": "COMMON_TLS",
  "customPorts": [],
  "connectTimeoutMs": 500,
  "tlsTimeoutMs": 3000
}
```

Existing `ScanJob` jobs implicitly have `jobType: "CERTIFICATE_SCAN"` (backward-compatible: absent field → old type).

**Port profiles:**

| Profile | Ports |
|---|---|
| `COMMON_TLS` | 443, 8443, 9443, 993, 995, 465, 990, 636, 6443, 8883, 5671, 5061, 5986 |
| `EXTENDED` | Above + 80, 8080, 8008, 3000, 4000, 5000, 8000, 8888, 9000, 9090, 9200 |
| `FULL` | 1–65535 (chunked; requires explicit org opt-in, rate-limited) |
| `CUSTOM` | User-supplied list (≤500 ports) |

### 3.2 Agent scan engine: `PortSweepScanner`

New class `agent/scanner/PortSweepScanner.java`. Pure Java 25, zero new dependencies, no elevated privileges.

**TCP connect sweep (virtual threads):**

```
for each host in CIDR (expand /24 → 254 hosts, /16 → 65534 hosts...):
  for each port in profile:
    virtual thread → Socket.connect(host:port, connectTimeoutMs)
    → OPEN (connect succeeded) | CLOSED (RST immediate) | FILTERED (timeout)
```

Use `Semaphore(maxConcurrentConnects)` (default 1000) to bound fan-out. Virtual threads (Java 25 `Thread.ofVirtual()`) make high-fan-out blocking connects cheap — no NIO reactor needed. `/24` × `COMMON_TLS` (~13 ports): **< 30 seconds** at 1000 concurrent with 500ms timeout.

**TLS probe on open ports:**

Extract a new method `SslScanner.probe(String host, int port) → X509Certificate[]` that calls the existing two-pass handshake logic but **bypasses the `targetId`-keyed serial cache** (discovered endpoints have no `targetId`). Returns:
- The peer certificate chain if TLS handshake succeeds
- `null` if connect succeeds but handshake fails → endpoint is `OPEN_NO_TLS`
- Exception propagation for `FILTERED`/`CLOSED` (already excluded by sweep step)

Endpoint states per port: `CLOSED_OR_FILTERED` | `OPEN_NO_TLS` | `OPEN_TLS`.

**Long-job hazard and chunking (DECIDED):**

The server-side stale-claim reaper (`AgentService.resetStaleClaimedJobs:428-443`) reclaims any CLAIMED job after 10 minutes. A full `/24` × `EXTENDED` scan can take longer. Mitigation: the server **chunks** large sweeps into sub-jobs of ≤100 hosts each before queuing. Each sub-job completes well within 10 min. The `network_scans` row tracks overall progress; sub-jobs reference it via `network_scan_id`.

### 3.3 Batch result contract

Current one-cert-per-POST is insufficient for a sweep that produces thousands of tuples. New endpoint:

**`POST /api/v1/agent/network-results`**

Request (HMAC over `networkScanId:chunkIndex:hostCount:timestamp`):

```json
{
  "networkScanId": "<uuid>",
  "chunkIndex": 0,
  "totalChunks": 4,
  "timestamp": 1751030400000,
  "hmac": "<hex>",
  "hosts": [
    {
      "ip": "192.168.1.10",
      "ports": [
        {
          "port": 443,
          "state": "OPEN_TLS",
          "chainB64": ["<base64-der>", "<base64-der>"],
          "deviceClass": "SERVER",
          "banners": { "http_server": "nginx/1.24.0", "tls_cn": "myapp.internal" }
        },
        {
          "port": 80,
          "state": "OPEN_NO_TLS"
        }
      ]
    },
    {
      "ip": "192.168.1.1",
      "ports": [
        {
          "port": 443,
          "state": "OPEN_TLS",
          "chainB64": ["<base64-der>"],
          "deviceClass": "ROUTER",
          "banners": { "http_title": "RouterOS", "ssh_version": "SSH-2.0-RouterOS_7.0" }
        }
      ]
    }
  ]
}
```

Response `202 Accepted` with `{ "accepted": N, "networkScanId": "...", "status": "IN_PROGRESS"|"COMPLETE" }`.

TLS chains flow through RFC 0009 `ChainValidationService` and `RevocationCheckService` server-side, same as agent-submitted cert scans. This preserves the `REVOKED > EXPIRED > INVALID > EXPIRING > VALID` precedence introduced in RFC 0009.

### 3.4 Schema — org-scoped

All tables use UUID PK, `created_at`/`updated_at` (DB trigger, Hibernate `@CreationTimestamp`/`@UpdateTimestamp`), and are org-scoped with a denormalized `org_id` per project convention.

```sql
-- Vn__add_network_scan_tables.sql

CREATE TYPE network_scan_status AS ENUM (
    'PENDING', 'IN_PROGRESS', 'COMPLETE', 'FAILED', 'CANCELLED'
);

CREATE TYPE port_scan_profile AS ENUM (
    'COMMON_TLS', 'EXTENDED', 'FULL', 'CUSTOM'
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

CREATE INDEX idx_network_scans_org ON network_scans(org_id);
CREATE INDEX idx_network_scans_agent ON network_scans(agent_id);
CREATE INDEX idx_network_scans_status ON network_scans(status) WHERE status IN ('PENDING','IN_PROGRESS');

CREATE TYPE endpoint_port_state AS ENUM (
    'OPEN_TLS', 'OPEN_NO_TLS', 'CLOSED_OR_FILTERED'
);

CREATE TYPE device_class AS ENUM (
    'ROUTER', 'SWITCH', 'SERVER', 'WORKSTATION', 'UNKNOWN'
);

CREATE TABLE discovered_endpoints (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    network_scan_id UUID NOT NULL REFERENCES network_scans(id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    ip              INET NOT NULL,
    port            INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535),
    state           endpoint_port_state NOT NULL,
    device_class    device_class NOT NULL DEFAULT 'UNKNOWN',
    banners         JSONB,
    -- populated when state = OPEN_TLS
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
CREATE INDEX idx_disc_endpoints_tls  ON discovered_endpoints(network_scan_id) WHERE state = 'OPEN_TLS';
```

### 3.5 Server APIs

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/organizations/{orgId}/network-scans` | JWT ADMIN/ENGINEER | Create and enqueue a sweep |
| `GET` | `/api/v1/organizations/{orgId}/network-scans` | JWT any | List sweeps (paginated) |
| `GET` | `/api/v1/organizations/{orgId}/network-scans/{id}` | JWT any | Status + counters |
| `GET` | `/api/v1/organizations/{orgId}/network-scans/{id}/endpoints` | JWT any | Discovered endpoints (paginated, filterable by `state`, `deviceClass`) |
| `DELETE` | `/api/v1/organizations/{orgId}/network-scans/{id}` | JWT ADMIN | Cancel pending/in-progress |
| `POST` | `/api/v1/agent/network-results` | Agent HMAC | Batch result submission |

**`POST /api/v1/organizations/{orgId}/network-scans` — request:**

```json
{
  "agentId": "<uuid>",
  "cidr": "192.168.1.0/24",
  "portProfile": "COMMON_TLS",
  "customPorts": []
}
```

Server validation before enqueue:
1. `@PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")` + `canAccessOrg(orgId)` (existing pattern)
2. `SubscriptionGuard.assertScansAllowed(orgId)` (reuse existing guard)
3. Agent belongs to org and is `ACTIVE`
4. Requested CIDR is a subset of `agents.allowed_cidrs` (reuse `AgentService.isInCidr:501-513`)
5. No other `IN_PROGRESS` or `PENDING` scan for this agent (one active sweep per agent at a time)
6. Write `OrgAuditService` entry (intrusive operation)

---

## 4. Part B — Anonymous Free-Tier Scan

### 4.1 Trust model: ephemeral dual-token session (DECIDED)

Anonymous visitors have no org, no `Agent` row. A new parallel low-trust surface is created — **not** a relaxation of the existing agent surface.

**Session creation:**

`POST /api/v1/anon/sessions` — public, no auth, IP-rate-limited (5 sessions / IP / 24h, global cap 10,000 active sessions).

Response:

```json
{
  "scanToken": "<64-char hex>",
  "viewToken": "<64-char hex>",
  "scanExpiresAt": "2026-06-28T13:00:00Z",
  "viewExpiresAt": "2026-07-05T12:00:00Z",
  "dashboardUrl": "https://certguard.io/scan/<viewToken>"
}
```

Both tokens are generated as `SecureRandom` 256-bit values; only their SHA-256 hashes are stored. `scanToken` authorises agent writes for ~1 hour. `viewToken` authorises read-only dashboard access for 7 days.

**Personalised download (DECIDED):** The server stamps `scanToken`, server base URL, and server TLS certificate SHA-256 fingerprint into a config file inside the agent ZIP at download time (mirroring the existing `AgentBundleService` bundle-stamp pattern). This preserves TLS certificate pinning (`SecureHttpClient` uses the fingerprint) without requiring the anonymous agent to enroll via the normal mTLS/HMAC credential flow.

**`AnonScanAuthFilter`:** New sibling to `AgentAuthFilter`. Reads `X-Anon-Scan-Token`, SHA-256 hashes it, resolves to an `anon_scan_sessions` row, rejects if expired or status is not `ACTIVE`. No `Agent` row, no org, no `AgentStatus`. Applies only to `/api/v1/anon/**` write paths.

### 4.2 Subnet discovery: local NIC inspection only (DECIDED)

The free-tier agent discovers subnets using `java.net.NetworkInterface.getNetworkInterfaces()`:

```java
NetworkInterface.networkInterfaces()
    .flatMap(ni -> ni.getInterfaceAddresses().stream())
    .filter(ia -> !ia.getAddress().isLoopbackAddress())
    .filter(ia -> !ia.getAddress().isLinkLocalAddress())  // filter fe80:: noise
    .filter(ia -> ia.getAddress() instanceof Inet4Address)
    .map(ia -> toCidr(ia.getAddress(), ia.getNetworkPrefixLength()))
    .distinct()
```

This covers every subnet the host is **directly attached to** (one per NIC / VLAN sub-interface). It does not discover remote routed subnets.

Router/switch extraction (SNMP, SSH, vendor REST) is **DEFERRED** to a post-signup opt-in credentialed flow. LLDP and TTL fingerprinting are **DECIDED OUT** (require root).

**Scope enforcement (DECIDED — HARD CONSTRAINT):**

The anonymous agent reports its discovered CIDRs to the server on first push. The server records them in `anon_discovered_subnets`. Any subsequent host IP in a result batch is validated server-side against:

1. RFC1918 / RFC4193 / RFC3927 allowlist: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `fc00::/7`, `169.254.0.0/16`
2. The CIDRs the agent itself reported (must be a subset of the above by definition, but explicitly checked)

Any result containing a public IP returns `400 Bad Request` with error code `SCOPE_VIOLATION`. The server is the authority; the agent also self-enforces (defense-in-depth).

### 4.3 Device classification (no privileges required)

The anonymous agent classifies each live host using only techniques available to an unprivileged Java process:

| Signal | Method | Implementation |
|---|---|---|
| Port fingerprint | TCP connect scan (virtual threads, same engine as Part A) | `PortSweepScanner` — zero new deps |
| HTTP banner / `<title>` | `GET /` with `HttpClient` (existing dep) | Parse `Server:` header + `<title>` tag |
| TLS cert CN / O field | Reuse `SslScanner.probe()` chain result | Already captured in TLS probe step |
| SSH version string | Read first plaintext line from port 22 on connect | `Socket` + `BufferedReader` — no SSH library |
| OUI / MAC vendor | Best-effort from `/proc/net/arp` (Linux only, OS-specific) | Sent to server; **server resolves OUI, never stored** |

**OUI resolution is server-side:** The IEEE OUI table (~1-2MB compacted) lives on the server, not in the agent JAR. The agent sends whatever MACs it best-effort collected from the OS ARP cache; the server resolves vendor names **in memory for display only** — vendor strings are never written to the database.

**Classification heuristics (server-side, applied at display time):**

| Device class | Indicators |
|---|---|
| `ROUTER` | Port 161/SNMP open, HTTP title contains "RouterOS"/"Cisco"/"pfSense"/"OpenWRT", SSH banner matches network-OS patterns, TLS CN matches router patterns |
| `SWITCH` | Port 161 open, HTTP title contains "ProCurve"/"EX Series"/"Catalyst", no high-numbered app ports |
| `SERVER` | Ports 22/80/443/8080/8443 open, HTTP `Server:` header present (nginx/Apache/IIS), no router/switch signals |
| `WORKSTATION` | Ports 5900/RDP/3389 open, no server ports, OS-style banners |
| `UNKNOWN` | No matching signals |

### 4.4 One-scan rule (DECIDED)

An anonymous agent may perform **exactly one scan** per session: the initial subnet/device discovery. The agent receives no further jobs after submitting its discovery results. The agent's poll loop must check for remaining jobs; when the server returns an empty job list after the discovery submission, the agent **terminates cleanly**.

Per-subnet drill-down (full port + SSL sweep) is **gated behind account creation and scan claim**. Anonymous sessions never receive a `NETWORK_SCAN` drill-down job. This rule is enforced server-side: `/api/v1/anon/jobs` returns an empty list once the session's discovery job has been claimed and completed.

### 4.5 Schema — anonymous (no org FK, display-only data, no MAC/IP persisted)

```sql
-- Vn+1__add_anon_scan_tables.sql

CREATE TYPE anon_session_status AS ENUM (
    'ACTIVE', 'SCAN_COMPLETE', 'CLAIMED', 'EXPIRED', 'DELETED'
);

CREATE TABLE anon_scan_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scan_token_hash     CHAR(64) NOT NULL UNIQUE,   -- SHA-256 hex of scanToken
    view_token_hash     CHAR(64) NOT NULL UNIQUE,   -- SHA-256 hex of viewToken
    status              anon_session_status NOT NULL DEFAULT 'ACTIVE',
    scan_expires_at     TIMESTAMPTZ NOT NULL,        -- ~1h from creation
    view_expires_at     TIMESTAMPTZ NOT NULL,        -- ~7d from creation
    claimed_by_org_id   UUID REFERENCES organizations(id),
    claimed_at          TIMESTAMPTZ,
    subnet_count        INTEGER NOT NULL DEFAULT 0,
    device_count        INTEGER NOT NULL DEFAULT 0,
    tls_found_count     INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
    -- NOTE: no client_ip column — not stored (DECIDED, privacy)
);

CREATE INDEX idx_anon_sessions_view_token ON anon_scan_sessions(view_token_hash);
CREATE INDEX idx_anon_sessions_status     ON anon_scan_sessions(status) WHERE status = 'ACTIVE';
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
    -- NOTE: no ip column — not stored (DECIDED, privacy); only display-tier data stored
    device_class    device_class NOT NULL DEFAULT 'UNKNOWN',
    open_port_count INTEGER NOT NULL DEFAULT 0,
    tls_port_count  INTEGER NOT NULL DEFAULT 0,
    open_ports      INTEGER[] NOT NULL DEFAULT '{}',
    banners         JSONB,          -- http_server, http_title, ssh_version, tls_cn, tls_o
    tls_subjects    TEXT[],         -- CN values from TLS certs found, for display
    tls_expiry_min  TIMESTAMPTZ,    -- earliest expiry across found certs, for display
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_anon_devices_session ON anon_discovered_devices(session_id);
CREATE INDEX idx_anon_devices_subnet  ON anon_discovered_devices(subnet_id);
```

**Privacy note:** No IP address, no MAC address, no OUI vendor string is written to the database. The `anon_discovered_devices` table stores aggregate port counts, banner strings, and TLS subject CNs (which may contain hostnames — flag for legal review). The `anon_discovered_subnets` table stores CIDRs (RFC1918 ranges only). This is the minimum needed to render the dashboard.

### 4.6 Server APIs — anonymous surface

All `/api/v1/anon/**` paths added to `SecurityConfig` `permitAll()` list. `AnonScanAuthFilter` applied to write paths (resolves `scanToken` header). Read paths use `viewToken` path variable. Gateway `PUBLIC_PATTERNS` must also be updated (see §5.3, Blocker B3).

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/anon/sessions` | None (IP-rate-limited) | Create session, return tokens |
| `GET` | `/api/v1/anon/download` | None | Serve personalised agent ZIP (token in query param, single-use) |
| `POST` | `/api/v1/anon/discovery-results` | `scanToken` | Agent pushes NIC subnets + discovered devices |
| `GET` | `/api/v1/anon/jobs` | `scanToken` | Agent polls; returns empty list once discovery is complete |
| `GET` | `/api/v1/anon/sessions/{viewToken}` | None | Read-only dashboard data |
| `POST` | `/api/v1/anon/sessions/{viewToken}/claim` | JWT (any role) | Claim scan into authenticated org |
| `DELETE` | `/api/v1/anon/sessions/{viewToken}` | None | Erasure (GDPR) |

**`POST /api/v1/anon/discovery-results` — request (`scanToken` in `X-Anon-Scan-Token` header):**

```json
{
  "subnets": [
    { "cidr": "192.168.1.0/24", "ifaceName": "eth0" },
    { "cidr": "10.0.0.0/24",   "ifaceName": "eth1" }
  ],
  "devices": [
    {
      "subnetCidr": "192.168.1.0/24",
      "deviceClass": "ROUTER",
      "openPorts": [22, 80, 443, 161],
      "tlsPortCount": 1,
      "banners": {
        "http_title": "RouterOS",
        "tls_cn": "router.local",
        "ssh_version": "SSH-2.0-RouterOS_7.0"
      },
      "tlsSubjects": ["router.local"],
      "tlsExpiryMin": "2027-01-15T00:00:00Z"
    }
  ]
}
```

Note: no IP addresses in the payload pushed to the server. The agent groups devices by subnet and device class but does not transmit individual host IPs.

**`GET /api/v1/anon/sessions/{viewToken}` — response:**

```json
{
  "status": "SCAN_COMPLETE",
  "scanExpiresAt": "...",
  "viewExpiresAt": "...",
  "summary": {
    "subnetCount": 2,
    "deviceCount": 14,
    "tlsFoundCount": 5,
    "routerCount": 1,
    "serverCount": 8,
    "expiringCertCount": 2
  },
  "subnets": [
    {
      "id": "<uuid>",
      "cidr": "192.168.1.0/24",
      "deviceCount": 8,
      "tlsCount": 3
    }
  ],
  "devices": [
    {
      "subnetCidr": "192.168.1.0/24",
      "deviceClass": "ROUTER",
      "openPorts": [22, 80, 443, 161],
      "banners": { "http_title": "RouterOS", "tls_cn": "router.local" },
      "tlsSubjects": ["router.local"],
      "tlsExpiryMin": "2027-01-15T00:00:00Z"
    }
  ]
}
```

### 4.7 Claim / hand-off flow

1. User creates an account (normal OAuth2 / password flow).
2. UI offers "Claim this scan" (viewToken present in browser — passed from `/scan/{viewToken}` page).
3. `POST /api/v1/anon/sessions/{viewToken}/claim` (JWT required) — server validates viewToken live, validates org membership, copies `anon_discovered_subnets` CIDRs to the org as seed data for the org's first `NETWORK_SCAN` job, marks session `CLAIMED`, sets `claimed_by_org_id`.
4. User is shown a "Run full scan" prompt — the claimed subnets are pre-populated in the "Scan Network" form.
5. Anon session data is purged immediately after successful claim (no 7-day retention needed).

Agent promotion (transitioning the running anonymous agent into an org agent in-place) is **DEFERRED**. v1: user installs the org-enrolled agent separately via the normal bundle download.

### 4.8 Anonymous session lifecycle

```
Session created → ACTIVE (scanToken valid ~1h)
  ↓ Agent submits discovery results
Session → SCAN_COMPLETE (scanToken expired; viewToken still valid ~7d)
  ↓ User signs up and claims  →  CLAIMED → purge immediately
  ↓ 7 days pass with no claim  →  EXPIRED → purge by AnonSessionPurgeScheduler
  ↓ User clicks "Delete my scan"  →  DELETED → purge immediately
```

`AnonSessionPurgeScheduler`: `@Scheduled` + `@SchedulerLock` (ShedLock, matching `CertificateExpiryScheduler` pattern), runs daily, hard-deletes rows with `view_expires_at < now()` or `status IN ('CLAIMED','DELETED')`.

---

## 5. Part C — Cross-Cutting

### 5.1 Security & abuse matrix

| Risk | Mitigation |
|---|---|
| **Scan abuse — arbitrary internet ranges** | Server-side hard allowlist: RFC1918 only + agent-reported CIDRs. Reject with `403 SCOPE_VIOLATION`. Non-negotiable, implemented at the result ingestion layer, not only at job dispatch. |
| **DDoS via mass session creation** | Rate-limit `POST /anon/sessions`: 5/IP/24h via a Redis sliding-window counter (or in-memory `ConcurrentHashMap` with TTL for single-node deployments); global active-session cap (10,000); CAPTCHA / proof-of-work on the download page |
| **Free topology-mapping service** | Write keyed to `scanToken` (hashed, TTL'd); read keyed to `viewToken` (separate, hashed); no list/enumeration endpoint; no cross-session reads; private-range restriction means an attacker can only map their own network |
| **Modified / abusive agent binaries** | All anonymous agent input treated as untrusted; server enforces scope + rate limits + payload caps (max 5 subnets, max 254 devices per session). Sign released JARs (jarsigner + checksum on download page). `scanToken` cannot reach org-scoped endpoints (separate filter chain + path prefix separation) |
| **One-scan enforcement bypass** | Server sets session `SCAN_COMPLETE` after first discovery result batch; subsequent calls to `POST /anon/discovery-results` return `409 ALREADY_SCANNED`. `GET /anon/jobs` returns empty list immediately once `SCAN_COMPLETE`. |

### 5.2 Chain & revocation reuse

Discovered TLS endpoints (Part A) must flow through `ChainValidationService` and `RevocationCheckService` (RFC 0009) server-side when storing results into `discovered_endpoints.cert_record_id`. This prevents the "leaf-only, no revocation" gap (GAPS N15) from being reintroduced on a new code path. The `tls_cert_status` column on `discovered_endpoints` uses the same `cert_status` ENUM and the same `REVOKED > EXPIRED > INVALID > EXPIRING > VALID` precedence rule.

Anonymous sessions (Part B) do not create `CertificateRecord` rows; TLS subjects and earliest expiry are stored as display-only strings in `anon_discovered_devices.tls_subjects` and `tls_expiry_min`. Full revocation checking for anonymous sessions is deferred.

### 5.3 Gateway change (Blocker B3 — cross-repo)

The Spring Cloud Gateway currently 401s any path not in `PUBLIC_PATTERNS` before requests reach the server (per prior incident documented in project memory: agent bundle download + agent runtime traffic had to be whitelisted there). The following patterns must be added to `PUBLIC_PATTERNS` in the gateway configuration:

```
/api/v1/anon/**
/scan/**
```

This change is in the **gateway repository**, not this server tree. It must be coordinated and deployed before the anonymous feature is tested end-to-end.

### 5.4 Rollout

- Part A (`NETWORK_SCAN` job type, authenticated) ships first, behind a `feature.network-scan.enabled` flag per org (set via platform-admin panel).
- Part B (anonymous free tier) ships second, behind a `feature.anon-scan.enabled` global flag. **Legal/privacy review is a go-live gate** — this flag defaults to `false` until review is complete.
- Both parts can be developed and merged independently; the shared agent scan engine (`PortSweepScanner`, extracted `SslScanner.probe()`) is a common dependency developed first.

### 5.5 Observability

New metrics (Micrometer, matching existing patterns):
- `certguard.network_scan.created` (tag: `org_id`, `port_profile`)
- `certguard.network_scan.duration_ms` (histogram)
- `certguard.network_scan.endpoints_discovered` (tag: `state`)
- `certguard.anon_session.created` (counter)
- `certguard.anon_session.claimed` (counter)
- `certguard.anon_session.scope_violation` (counter — alert on spike)

---

## 6. Open Questions

| # | Question | Owner |
|---|---|---|
| Q1 | Should `FULL` (1–65535) port profile require a support ticket / explicit platform-admin enablement per org, or is a subscription tier sufficient? | Product |
| Q2 | Should TLS certs discovered by the authenticated network scan automatically create `Target` rows (opt-in), or remain in `discovered_endpoints` as a separate inventory? | Product |
| Q3 | Post-signup agent promotion: is it worth building in-place anonymous→org agent transition in v1.1, or is re-download acceptable UX? | Product / UX |
| Q4 | SNMP deep-discovery (post-signup): which SNMP library (SNMP4J ~1MB) — acceptable JAR size increase, or keep the agent JAR strictly dep-free? | Eng |
| Q5 | Should `tls_subjects` (stored in `anon_discovered_devices`) be considered PII under GDPR? Internal hostnames are generally not, but confirm with legal. | Legal |
| Q6 | Rate-limiting `POST /anon/sessions` — Redis (existing infra?) or in-memory only? | Eng |

---

## 7. Decision Log

| # | Decision | Rationale |
|---|---|---|
| D1 | Pure-Java virtual-thread TCP connect scan; no nmap/masscan/SYN | Preserves no-root agent model; zero new deps; Java 25 virtual threads make fan-out cheap |
| D2 | Chunked sub-jobs for large sweeps | Avoids 10-min stale-claim reaper double-execution (`AgentService.java:428-443`) |
| D3 | Batched result endpoint `/api/v1/agent/network-results` | One-cert-per-POST contract does not scale to sweep result volumes |
| D4 | STARTTLS deferred | Per-protocol handlers (SMTP/IMAP/LDAP/PG) are non-trivial; direct-TLS covers highest-value ports; STARTTLS is additive |
| D5 | Anonymous scope = RFC1918 + agent-reported CIDRs only | Non-negotiable to prevent CertGuard becoming an open internet scanner; abuse + legal liability |
| D6 | MAC / client IP not stored (DECIDED by product owner 2026-06-28) | Feature is for marketing; PII risk under GDPR (CJEU Breyer); ephemeral display-only is sufficient |
| D7 | No second scan without signup (DECIDED by product owner 2026-06-28) | Abuse prevention; drives conversion; one wow-moment scan is the marketing intent |
| D8 | OUI resolution server-side | Keeps agent JAR small; OUI table (~1-2MB) on server; vendor strings never persisted |
| D9 | LLDP / TTL fingerprint / raw-socket techniques excluded | Require root/CAP_NET_RAW; break the no-privilege agent model; cannot ship |
| D10 | Router/switch SNMP/SSH/REST extraction deferred to post-signup | Requires device credentials; not safe for a zero-auth surface; additive for enterprise tier |
| D11 | Per-subnet drill-down gated behind signup + claim | Prevents unlimited free scanning; drives the core conversion action |
| D12 | Agent promotion deferred | Complex state transfer; re-download is acceptable v1 UX |

---

## 8. Blockers

| # | Blocker | Resolution |
|---|---|---|
| B1 | No data-model home for discovered endpoints — schema is entirely target-centric | New Flyway migrations (§3.4, §4.5) are prerequisite for any implementation |
| B2 | No `allowed_cidrs` boundary for anonymous sessions | Server-side RFC1918 + agent-reported CIDR allowlist (§4.2) — must be implemented before anonymous result ingestion opens |
| B3 | Gateway `PUBLIC_PATTERNS` does not include `/api/v1/anon/**` — will 401 before reaching server | Cross-repo gateway change (§5.3) must be coordinated and deployed |
| B4 | Long-running sweeps vs `resetStaleClaimedJobs` 10-min reaper | Chunking (§3.2, D2) is the fix; do not implement `NETWORK_SCAN` without it |
| B5 | Legal/privacy review required before anonymous tier goes live | Feature flag `feature.anon-scan.enabled` defaults `false`; legal sign-off is the gate |

---

## 9. Effort Estimate (S ≤ 2d, M ~ 1 wk, L ~ 2–3 wks)

### Part A — Authenticated Network Scan

| Component | Size |
|---|---|
| Agent: `PortSweepScanner` (virtual-thread TCP connect, port profiles) | M |
| Agent: extract `SslScanner.probe(host,port)` bypassing serial cache | S |
| Agent: `NETWORK_SCAN` job parsing, chunked sub-job loop, batched result POST | M |
| Server: Flyway migration — `network_scans` + `discovered_endpoints` + new ENUMs | M |
| Server: `POST /api/v1/agent/network-results` — batch ingest + HMAC validation | M |
| Server: `POST .../network-scans` — create/validate/enqueue (CIDR scope, SubscriptionGuard) | M |
| Server: `GET .../network-scans/{id}/endpoints` — read APIs | S |
| Server: stale-reaper exemption / sub-job chunk orchestration | S |
| Server: chain/revocation wiring for discovered TLS (RFC 0009 services) | S |
| UI: Scan Network form + agent selector + port profile picker | M |
| UI: Progress poll + results table (endpoints, device class, TLS status) | M |

### Part B — Anonymous Free-Tier Scan

| Component | Size |
|---|---|
| Agent: local NIC subnet discovery (`NetworkInterface`) | S |
| Agent: device classification (port fingerprint + HTTP/SSH/TLS banners) | M |
| Agent: anonymous session mode (`scanToken` auth, poll/push, one-scan termination) | M |
| Agent: personalised anon download packaging (token stamp into ZIP) | S |
| Server: `AnonScanAuthFilter` + `/api/v1/anon/**` security wiring | M |
| Server: Flyway migration — `anon_scan_sessions` + `anon_discovered_subnets` + `anon_discovered_devices` | M |
| Server: anon session create / discovery-results / jobs / read / delete endpoints | M |
| Server: RFC1918 + agent-reported CIDR allowlist + payload size caps + rate limiting | M |
| Server: OUI lookup table (server-side, display-only) | S |
| Server: `AnonSessionPurgeScheduler` (ShedLock, daily) | S |
| Server: claim/hand-off flow (copy subnets to org, trigger NETWORK_SCAN prompt) | M |
| Gateway: `PUBLIC_PATTERNS` update (cross-repo) | S |
| UI: `/scan/{viewToken}` anonymous read-only dashboard | M |
| UI: Download / CTA page (CAPTCHA, privacy notice, checksum display) | M |

### Deferred (not in this RFC's scope)

| Component | Size |
|---|---|
| STARTTLS handlers (SMTP first, then IMAP/LDAP/PG) | M each |
| SNMP deep discovery, post-signup (SNMP4J integration, opt-in) | M |
| SSH CLI scrape (Cisco/Junos/Arista), post-signup, opt-in | L |
| Agent promotion (anonymous → org agent in-place) | M–L |
| WebSocket / SSE progress streaming | M |
| `FULL` port profile (1–65535) safeguards + platform-admin gating | S |

**Total in-scope estimate:** ~14–18 weeks across backend, agent, and frontend — developed in two sequential phases (Part A first, then Part B).

---

## 10. Appendix: Sequence Diagrams

### A. Authenticated network scan

```
UI                  Server              Agent DB            Agent
|                   |                   |                   |
| POST /network-scans (CIDR, profile)   |                   |
|------------------>|                   |                   |
|                   | validate CIDR ⊆ allowed_cidrs         |
|                   | chunk → N sub-jobs|                   |
|                   | INSERT network_scans (PENDING)         |
|                   | INSERT agent_scan_jobs x N             |
|<-- 202 {id}  -----|                   |                   |
|                   |                   |                   |
| GET /network-scans/{id} (poll)        |                   |
|------------------>|                   |                   |
|<-- {status: IN_PROGRESS, hostsScanned: 0} -|             |
|                   |                   |                   |
|                   |        GET /api/v1/agent/jobs         |
|                   |<----------------------------------------|
|                   |-- [{jobType:NETWORK_SCAN, cidr:...}] -->|
|                   |                   |                   |
|                   |                   |    PortSweepScanner + SslScanner.probe()
|                   |                   |                   |
|                   |        POST /api/v1/agent/network-results (batch)
|                   |<----------------------------------------|
|                   | ChainValidation + RevocationCheck       |
|                   | INSERT discovered_endpoints             |
|                   | UPDATE network_scans (progress)        |
|                   |-- 202 ACCEPTED ----------------------->|
|                   |                   |                   |
| GET /network-scans/{id} (poll)        |                   |
|<-- {status: COMPLETE, tlsFoundCount: 42} --|             |
```

### B. Anonymous scan + claim

```
Browser             Server              Agent
|                   |                   |
| POST /anon/sessions                   |
|------------------>|                   |
|<-- {scanToken, viewToken, dashboardUrl} --|
|                   |                   |
| GET /anon/download?token=<scanToken>  |
|-- (personalised agent ZIP) ---------->|
|                   |                   |
|                   |   [agent runs on user's machine]
|                   |                   |
|                   |  GET /api/v1/anon/jobs (X-Anon-Scan-Token)
|                   |<------------------|
|                   |-- [{jobType:DISCOVERY}] -->|
|                   |                   |
|                   |   NIC discovery + port sweep + banner grab
|                   |                   |
|                   |  POST /api/v1/anon/discovery-results
|                   |<------------------|
|                   | validate scope (RFC1918 + reported CIDRs)
|                   | INSERT anon_discovered_subnets/devices (no IP/MAC)
|                   | UPDATE session → SCAN_COMPLETE
|                   |-- 200 OK -------->|
|                   |                   |
|                   |  GET /api/v1/anon/jobs → [] (empty — one-scan rule)
|                   |<------------------|
|                   |-- [] ------------>| (agent terminates)
|                   |                   |
| GET /scan/{viewToken}                 |
|------------------>|                   |
|<-- {subnets, devices, summary} -------|
|                   |                   |
| [user signs up]   |                   |
|                   |                   |
| POST /anon/sessions/{viewToken}/claim (JWT)
|------------------>|                   |
|                   | copy subnets → org
|                   | mark CLAIMED → purge anon rows
|<-- 200 {networkScanPrompt} ----------|
|                   |                   |
| [full NETWORK_SCAN via Part A flow]   |
```
