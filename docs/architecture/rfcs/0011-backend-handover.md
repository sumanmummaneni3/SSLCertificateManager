# RFC 0011 — Backend Engineer Handover

You are building the server-side and agent-side implementation of RFC 0011: Network Discovery & TLS Sweep. This covers two independently shippable parts — an authenticated network scan for registered org users (Part A), and a free-tier anonymous scan for unauthenticated marketing visitors (Part B). Read `docs/architecture/rfcs/0011-network-discovery-and-tls-sweep.md` for full design rationale. This doc gives you every file path, class skeleton, and implementation sequence you need to start coding.

---

## Repository layout

- Server: `server/src/main/java/com/certguard/`
- Server migrations: `server/src/main/resources/db/migration/`
- Agent: (plain Java 25, no Spring) — `agent/src/main/java/com/certguard/agent/` *(confirm exact path with `find . -name AgentMain.java`)*
- Latest migration version on disk: `V9__cert_alert_dedup.sql` → next is **V10**

---

## Part A — Authenticated Network Scan

### Step 1: Flyway migration `V10__network_scan_tables.sql`

Create `server/src/main/resources/db/migration/V10__network_scan_tables.sql`:

```sql
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
```

### Step 2: New enums

**`server/.../enums/NetworkScanStatus.java`**
```java
package com.certguard.enums;
public enum NetworkScanStatus { PENDING, IN_PROGRESS, COMPLETE, FAILED, CANCELLED }
```

**`server/.../enums/PortScanProfile.java`**
```java
package com.certguard.enums;
public enum PortScanProfile { COMMON_TLS, EXTENDED, FULL, CUSTOM }
```

**`server/.../enums/EndpointPortState.java`**
```java
package com.certguard.enums;
public enum EndpointPortState { OPEN_TLS, OPEN_NO_TLS, CLOSED_OR_FILTERED }
```

**`server/.../enums/DeviceClass.java`**
```java
package com.certguard.enums;
public enum DeviceClass { ROUTER, SWITCH, SERVER, WORKSTATION, UNKNOWN }
```

Add `NETWORK_SCAN` to existing `AgentJobType.java` (or create that enum if it doesn't already exist as a discriminator).

### Step 3: JPA entities

**`server/.../entity/NetworkScan.java`** — extend `BaseEntity`:
```java
@Entity @Table(name = "network_scans")
@Getter @Setter
public class NetworkScan extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(name = "org_id", insertable = false, updatable = false)
    private UUID orgId;  // denormalized for query performance

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @Column(nullable = false)
    private String cidr;

    @Enumerated(EnumType.STRING)
    @Column(name = "port_profile", nullable = false,
            columnDefinition = "port_scan_profile")
    private PortScanProfile portProfile;

    @Column(name = "custom_ports", columnDefinition = "integer[]")
    private int[] customPorts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "network_scan_status")
    private NetworkScanStatus status = NetworkScanStatus.PENDING;

    private Integer hostsTotal;
    private int hostsScanned;
    private int openPortCount;
    private int tlsFoundCount;
    private String errorMessage;
}
```

**`server/.../entity/DiscoveredEndpoint.java`** — extend `BaseEntity`:
```java
@Entity @Table(name = "discovered_endpoints")
@Getter @Setter
public class DiscoveredEndpoint extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "network_scan_id", nullable = false, updatable = false)
    private NetworkScan networkScan;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;  // denormalized

    @Column(nullable = false, columnDefinition = "inet")
    private String ip;

    @Column(nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "endpoint_port_state")
    private EndpointPortState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_class", columnDefinition = "device_class")
    private DeviceClass deviceClass = DeviceClass.UNKNOWN;

    @Column(columnDefinition = "jsonb")
    private String banners;  // JSON string; parse with ObjectMapper where needed

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cert_record_id")
    private CertificateRecord certRecord;

    private String tlsSubjectCn;
    private Instant tlsNotAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "tls_cert_status", columnDefinition = "cert_status")
    private CertStatus tlsCertStatus;
}
```

### Step 4: Repositories

**`server/.../repository/NetworkScanRepository.java`**
```java
public interface NetworkScanRepository extends JpaRepository<NetworkScan, UUID> {
    Page<NetworkScan> findByOrgIdOrderByCreatedAtDesc(UUID orgId, Pageable pageable);
    Optional<NetworkScan> findByIdAndOrgId(UUID id, UUID orgId);
    List<NetworkScan> findByAgentIdAndStatusIn(UUID agentId, List<NetworkScanStatus> statuses);
}
```

**`server/.../repository/DiscoveredEndpointRepository.java`**
```java
public interface DiscoveredEndpointRepository extends JpaRepository<DiscoveredEndpoint, UUID> {
    Page<DiscoveredEndpoint> findByNetworkScanId(UUID scanId, Pageable pageable);
    Page<DiscoveredEndpoint> findByNetworkScanIdAndState(UUID scanId, EndpointPortState state, Pageable pageable);
    Page<DiscoveredEndpoint> findByNetworkScanIdAndDeviceClass(UUID scanId, DeviceClass deviceClass, Pageable pageable);
}
```

### Step 5: DTOs

**`server/.../dto/request/NetworkScanCreateRequest.java`**
```java
public record NetworkScanCreateRequest(
    @NotNull UUID agentId,
    @NotBlank @Pattern(regexp = "^(\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$") String cidr,
    @NotNull PortScanProfile portProfile,
    List<@Min(1) @Max(65535) Integer> customPorts
) {}
```

**`server/.../dto/response/NetworkScanResponse.java`**
```java
public record NetworkScanResponse(
    UUID id, UUID orgId, UUID agentId, String agentName,
    String cidr, PortScanProfile portProfile,
    NetworkScanStatus status,
    Integer hostsTotal, int hostsScanned, int openPortCount, int tlsFoundCount,
    String errorMessage, Instant createdAt, Instant updatedAt
) {}
```

**`server/.../dto/response/DiscoveredEndpointResponse.java`**
```java
public record DiscoveredEndpointResponse(
    UUID id, UUID networkScanId,
    String ip, int port,
    EndpointPortState state, DeviceClass deviceClass,
    Map<String, String> banners,
    UUID certRecordId, String tlsSubjectCn,
    Instant tlsNotAfter, CertStatus tlsCertStatus,
    Instant createdAt
) {}
```

**`server/.../dto/request/AgentNetworkResultsBatch.java`** — received from agent:
```java
public record AgentNetworkResultsBatch(
    @NotNull UUID networkScanId,
    int chunkIndex,
    int totalChunks,
    long timestamp,
    @NotBlank String hmac,
    @NotNull List<HostResult> hosts
) {
    public record HostResult(
        @NotBlank String ip,
        @NotNull List<PortResult> ports
    ) {}

    public record PortResult(
        @Min(1) @Max(65535) int port,
        @NotNull EndpointPortState state,
        List<String> chainB64,      // base64-DER encoded certs, OPEN_TLS only
        DeviceClass deviceClass,
        Map<String, String> banners
    ) {}
}
```

### Step 6: `NetworkScanService.java`

**`server/.../service/NetworkScanService.java`**

```java
@Service
@Transactional(readOnly = true)
public class NetworkScanService {

    // Dependencies to inject:
    //   NetworkScanRepository, DiscoveredEndpointRepository, AgentRepository,
    //   AgentService (for isInCidr), SubscriptionGuard, OrgAuditService,
    //   AgentHmacService, ChainValidationService, RevocationCheckService,
    //   ExpiryEvaluationService, ObjectMapper

    /** Validate, create, and enqueue a network sweep. */
    @Transactional
    public NetworkScanResponse createScan(UUID orgId, NetworkScanCreateRequest req, UUID requestingUserId) {
        // 1. subscriptionGuard.assertScansAllowed(orgId)
        // 2. Verify agent belongs to org and is ACTIVE
        // 3. Validate req.cidr() is a subnet of agents.allowed_cidrs
        //    → reuse AgentService.isInCidr(agentAllowedCidrs, req.cidr())
        // 4. Check no PENDING/IN_PROGRESS scan already exists for this agent
        //    → networkScanRepository.findByAgentIdAndStatusIn(agentId, List.of(PENDING, IN_PROGRESS))
        // 5. INSERT NetworkScan(status=PENDING)
        // 6. orgAuditService.record(orgId, requestingUserId, "NETWORK_SCAN_CREATED", scanId)
        // 7. Return NetworkScanResponse
    }

    public Page<NetworkScanResponse> listScans(UUID orgId, Pageable pageable) { ... }

    public NetworkScanResponse getScan(UUID orgId, UUID scanId) { ... }

    /** Called by AgentController when batch results arrive. */
    @Transactional
    public void ingestBatchResults(AgentNetworkResultsBatch batch, Agent agent) {
        // 1. Validate HMAC: SHA-256-HMAC over
        //    "{networkScanId}:{chunkIndex}:{hostCount}:{timestamp}"
        //    using agent.getAgentKeyHash() — see AgentHmacService for pattern
        // 2. Load NetworkScan, verify agent owns it and it's IN_PROGRESS
        // 3. For each HostResult.PortResult:
        //    a. If state == OPEN_TLS and chainB64 != null:
        //       - Decode chain: Base64 → X509Certificate[]
        //       - ChainValidationService.validate(chain) → ChainValidationResult
        //       - RevocationCheckService.check(chain) → RevocationResult
        //       - ExpiryEvaluationService.determineCertStatus(leaf) → CertStatus
        //       - Apply precedence: REVOKED > EXPIRED > INVALID > EXPIRING > VALID
        //       - INSERT CertificateRecord (or dedupe on serial) — reuse existing
        //         AgentService.persistCertificate pattern
        //       - INSERT DiscoveredEndpoint(state=OPEN_TLS, certRecordId=...)
        //    b. Else: INSERT DiscoveredEndpoint(state=..., certRecordId=null)
        // 4. UPDATE NetworkScan counters (hostsScanned, openPortCount, tlsFoundCount)
        // 5. If chunkIndex == totalChunks - 1: SET status = COMPLETE
    }

    @Transactional
    public void cancelScan(UUID orgId, UUID scanId) { ... }
}
```

**HMAC validation for batch results** — compute over:
```
HMAC-SHA-256(key=agentKeyRaw, data="{networkScanId}:{chunkIndex}:{hostCount}:{timestamp}")
```
Reference `AgentHmacService.java` for the existing `verify(...)` pattern. The agent sends the raw `agentKey` in its `X-Agent-Key` header, which the filter stores in `request.getAttribute("authenticatedAgentKey")`.

### Step 7: `NetworkScanController.java`

**`server/.../controller/NetworkScanController.java`**

```java
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/network-scans")
@Transactional(readOnly = true)
public class NetworkScanController {

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER') and @mspAccessGuard.canAccessOrg(#orgId)")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public NetworkScanResponse createScan(
        @PathVariable UUID orgId,
        @RequestBody @Valid NetworkScanCreateRequest req,
        @AuthenticationPrincipal CertGuardUserPrincipal principal
    ) { ... }

    @GetMapping
    @PreAuthorize("@mspAccessGuard.canAccessOrg(#orgId)")
    public Page<NetworkScanResponse> listScans(
        @PathVariable UUID orgId,
        Pageable pageable
    ) { ... }

    @GetMapping("/{scanId}")
    @PreAuthorize("@mspAccessGuard.canAccessOrg(#orgId)")
    public NetworkScanResponse getScan(
        @PathVariable UUID orgId,
        @PathVariable UUID scanId
    ) { ... }

    @GetMapping("/{scanId}/endpoints")
    @PreAuthorize("@mspAccessGuard.canAccessOrg(#orgId)")
    public Page<DiscoveredEndpointResponse> listEndpoints(
        @PathVariable UUID orgId,
        @PathVariable UUID scanId,
        @RequestParam(required = false) EndpointPortState state,
        @RequestParam(required = false) DeviceClass deviceClass,
        Pageable pageable
    ) { ... }

    @DeleteMapping("/{scanId}")
    @PreAuthorize("hasRole('ADMIN') and @mspAccessGuard.canAccessOrg(#orgId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelScan(
        @PathVariable UUID orgId,
        @PathVariable UUID scanId
    ) { ... }
}
```

### Step 8: Agent batch result endpoint

Add to **`server/.../controller/AgentController.java`** (or a new `AgentNetworkController.java`):

```java
@PostMapping("/api/v1/agent/network-results")
public ResponseEntity<Map<String, Object>> submitNetworkResults(
    @RequestBody @Valid AgentNetworkResultsBatch batch,
    HttpServletRequest request
) {
    Agent agent = (Agent) request.getAttribute("authenticatedAgent");
    networkScanService.ingestBatchResults(batch, agent);
    return ResponseEntity.accepted().body(Map.of(
        "accepted", batch.hosts().size(),
        "networkScanId", batch.networkScanId(),
        "status", "IN_PROGRESS"
    ));
}
```

This endpoint is already guarded by `AgentAuthFilter` (path starts with `/api/v1/agent/`).

### Step 9: `SecurityConfig.java` changes

No changes needed for Part A — all new endpoints are under `/api/v1/**` which is already `.authenticated()`, and role checks are done via `@PreAuthorize`.

---

## Part B — Anonymous Free-Tier Scan

### Step 10: Flyway migration `V11__anon_scan_tables.sql`

Create `server/src/main/resources/db/migration/V11__anon_scan_tables.sql`:

```sql
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
```

### Step 11: New enum

**`server/.../enums/AnonSessionStatus.java`**
```java
public enum AnonSessionStatus { ACTIVE, SCAN_COMPLETE, CLAIMED, EXPIRED, DELETED }
```

### Step 12: JPA entities (anon)

**`server/.../entity/AnonScanSession.java`** — does NOT extend `BaseEntity` (no org FK):
```java
@Entity @Table(name = "anon_scan_sessions")
@Getter @Setter
public class AnonScanSession {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "scan_token_hash", nullable = false, unique = true, length = 64)
    private String scanTokenHash;   // SHA-256 hex of raw scanToken

    @Column(name = "view_token_hash", nullable = false, unique = true, length = 64)
    private String viewTokenHash;   // SHA-256 hex of raw viewToken

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "anon_session_status")
    private AnonSessionStatus status = AnonSessionStatus.ACTIVE;

    @Column(name = "scan_expires_at", nullable = false)
    private Instant scanExpiresAt;

    @Column(name = "view_expires_at", nullable = false)
    private Instant viewExpiresAt;

    @Column(name = "claimed_by_org_id")
    private UUID claimedByOrgId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    private int subnetCount;
    private int deviceCount;
    private int tlsFoundCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

**`server/.../entity/AnonDiscoveredSubnet.java`**
```java
@Entity @Table(name = "anon_discovered_subnets")
@Getter @Setter
public class AnonDiscoveredSubnet {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false) private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private AnonScanSession session;

    @Column(nullable = false) private String cidr;
    private String ifaceName;
    @Column(nullable = false) private String source = "LOCAL_NIC";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false) private Instant createdAt;
}
```

**`server/.../entity/AnonDiscoveredDevice.java`**
```java
@Entity @Table(name = "anon_discovered_devices")
@Getter @Setter
public class AnonDiscoveredDevice {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false) private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private AnonScanSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subnet_id", nullable = false, updatable = false)
    private AnonDiscoveredSubnet subnet;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_class", columnDefinition = "device_class")
    private DeviceClass deviceClass = DeviceClass.UNKNOWN;

    private int openPortCount;
    private int tlsPortCount;

    @Column(columnDefinition = "integer[]")
    private int[] openPorts;

    @Column(columnDefinition = "jsonb") private String banners;

    @Column(columnDefinition = "text[]") private String[] tlsSubjects;
    private Instant tlsExpiryMin;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at") private Instant updatedAt;
}
```

### Step 13: Repositories (anon)

```java
public interface AnonScanSessionRepository extends JpaRepository<AnonScanSession, UUID> {
    Optional<AnonScanSession> findByScanTokenHash(String hash);
    Optional<AnonScanSession> findByViewTokenHash(String hash);
    List<AnonScanSession> findByViewExpiresAtBeforeOrStatusIn(
        Instant cutoff, List<AnonSessionStatus> statuses);
}

public interface AnonDiscoveredSubnetRepository extends JpaRepository<AnonDiscoveredSubnet, UUID> {
    List<AnonDiscoveredSubnet> findBySessionId(UUID sessionId);
}

public interface AnonDiscoveredDeviceRepository extends JpaRepository<AnonDiscoveredDevice, UUID> {
    List<AnonDiscoveredDevice> findBySessionId(UUID sessionId);
}
```

### Step 14: DTOs (anon)

**`server/.../dto/request/AnonDiscoveryResultsRequest.java`**
```java
public record AnonDiscoveryResultsRequest(
    @NotNull List<SubnetDto> subnets,
    @NotNull List<DeviceDto> devices
) {
    public record SubnetDto(
        @NotBlank String cidr,
        String ifaceName
    ) {}

    public record DeviceDto(
        @NotBlank String subnetCidr,
        DeviceClass deviceClass,
        @NotNull List<@Min(1) @Max(65535) Integer> openPorts,
        int tlsPortCount,
        Map<String, String> banners,
        List<String> tlsSubjects,
        Instant tlsExpiryMin
    ) {}
}
```

**`server/.../dto/response/AnonSessionCreateResponse.java`**
```java
public record AnonSessionCreateResponse(
    String scanToken,
    String viewToken,
    Instant scanExpiresAt,
    Instant viewExpiresAt,
    String dashboardUrl
) {}
```

**`server/.../dto/response/AnonSessionDashboardResponse.java`**
```java
public record AnonSessionDashboardResponse(
    AnonSessionStatus status,
    Instant scanExpiresAt,
    Instant viewExpiresAt,
    SummaryDto summary,
    List<SubnetDto> subnets,
    List<DeviceDto> devices
) {
    public record SummaryDto(
        int subnetCount, int deviceCount, int tlsFoundCount,
        int routerCount, int serverCount
    ) {}
    public record SubnetDto(UUID id, String cidr, int deviceCount, int tlsCount) {}
    public record DeviceDto(
        UUID id, String subnetCidr, DeviceClass deviceClass,
        int[] openPorts, Map<String, String> banners,
        List<String> tlsSubjects, Instant tlsExpiryMin
    ) {}
}
```

### Step 15: `AnonScanAuthFilter.java`

Modelled exactly on `AgentAuthFilter`. Place in `server/.../security/AnonScanAuthFilter.java`:

```java
@Slf4j
public class AnonScanAuthFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Anon-Scan-Token";

    // Write paths that require a valid scanToken
    private static final Set<String> WRITE_PREFIXES = Set.of(
        "/api/v1/anon/discovery-results",
        "/api/v1/anon/jobs"
    );

    private final AnonScanSessionRepository sessionRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return WRITE_PREFIXES.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String rawToken = request.getHeader(TOKEN_HEADER);
        if (rawToken == null || rawToken.isBlank()) {
            sendUnauthorized(response, "Missing X-Anon-Scan-Token");
            return;
        }

        String hash = sha256Hex(rawToken);
        AnonScanSession session = sessionRepository.findByScanTokenHash(hash).orElse(null);

        if (session == null || session.getStatus() != AnonSessionStatus.ACTIVE
                || Instant.now().isAfter(session.getScanExpiresAt())) {
            sendUnauthorized(response, "Invalid or expired scan token");
            return;
        }

        request.setAttribute("anonSession", session);
        // No Spring Security principal needed — the filter sets the session attribute,
        // which the controller reads directly via request.getAttribute("anonSession")
        chain.doFilter(request, response);
    }

    private String sha256Hex(String input) {
        // MessageDigest.getInstance("SHA-256"), encode to hex
    }

    private void sendUnauthorized(HttpServletResponse response, String detail)
            throws IOException {
        response.setStatus(401);
        response.setContentType("application/problem+json");
        response.getWriter().write(
            "{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"" + detail + "\"}");
    }
}
```

### Step 16: `SecurityConfig.java` changes

In `filterChain()`, add two things:

1. Permit anon paths **before** the catch-all `.requestMatchers("/api/v1/**").authenticated()`:
```java
.requestMatchers("/api/v1/anon/sessions").permitAll()        // POST create
.requestMatchers("/api/v1/anon/download").permitAll()        // GET download
.requestMatchers(HttpMethod.GET, "/api/v1/anon/sessions/*").permitAll()   // GET dashboard
.requestMatchers(HttpMethod.DELETE, "/api/v1/anon/sessions/*").permitAll() // DELETE erasure
// Write paths (/discovery-results, /jobs) are guarded by AnonScanAuthFilter — not JWT
.requestMatchers("/api/v1/anon/discovery-results").permitAll()
.requestMatchers("/api/v1/anon/jobs").permitAll()
// Claim requires JWT (user must be logged in)
.requestMatchers(HttpMethod.POST, "/api/v1/anon/sessions/*/claim").authenticated()
```

2. Register `AnonScanAuthFilter` before `UsernamePasswordAuthenticationFilter` (alongside the other filters):
```java
.addFilterBefore(anonScanAuthFilter, UsernamePasswordAuthenticationFilter.class)
```

### Step 17: `AnonScanService.java`

```java
@Service
@Transactional(readOnly = true)
public class AnonScanService {

    private static final Duration SCAN_TTL = Duration.ofHours(1);
    private static final Duration VIEW_TTL = Duration.ofDays(7);
    // RFC1918 allowlist
    private static final List<String> PRIVATE_RANGES =
        List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    /**
     * Creates a new anonymous session. IP-rate-limiting must be enforced
     * by the caller (controller reads X-Forwarded-For but does NOT store it).
     */
    @Transactional
    public AnonSessionCreateResponse createSession(String serverBaseUrl) {
        // 1. Generate two SecureRandom 256-bit tokens, hex-encode → rawScanToken, rawViewToken
        // 2. sha256Hex each → scanTokenHash, viewTokenHash
        // 3. INSERT AnonScanSession (hashes only, no IP)
        // 4. Return AnonSessionCreateResponse with RAW tokens (never stored again)
    }

    /**
     * Agent pushes NIC subnets + discovered devices.
     * One-scan rule: once called successfully, session moves to SCAN_COMPLETE.
     */
    @Transactional
    public void ingestDiscoveryResults(AnonScanSession session,
                                       AnonDiscoveryResultsRequest req) {
        // 1. If session.status != ACTIVE → throw 409 ALREADY_SCANNED
        // 2. Validate all CIDRs are within PRIVATE_RANGES
        //    → if any violates: throw 400 SCOPE_VIOLATION
        // 3. Validate payload caps: max 5 subnets, max 254 devices per session
        // 4. INSERT AnonDiscoveredSubnet for each req.subnets()
        // 5. For each req.devices(): resolve subnet by cidr, INSERT AnonDiscoveredDevice
        //    (no IP stored — deviceClass, openPorts, banners, tlsSubjects, tlsExpiryMin only)
        // 6. UPDATE session: status=SCAN_COMPLETE, counters
    }

    /** Called by the agent's poll endpoint. Returns empty once scan is complete. */
    public List<Object> getPendingJobs(AnonScanSession session) {
        if (session.getStatus() != AnonSessionStatus.ACTIVE) return List.of();
        // Return a DISCOVERY job stub if one hasn't been completed yet
        // Once SCAN_COMPLETE, always return []
    }

    public AnonSessionDashboardResponse getSessionForView(String rawViewToken) {
        String hash = sha256Hex(rawViewToken);
        AnonScanSession session = sessionRepository.findByViewTokenHash(hash)
            .orElseThrow(() -> new ResourceNotFoundException("Session not found"));
        if (Instant.now().isAfter(session.getViewExpiresAt()))
            throw new ResourceNotFoundException("Session expired");
        // Load subnets + devices, map to response
    }

    /** POST /api/v1/anon/sessions/{viewToken}/claim — JWT required. */
    @Transactional
    public void claimSession(String rawViewToken, UUID orgId, UUID userId) {
        // 1. Resolve session by viewTokenHash
        // 2. Copy anon_discovered_subnets CIDRs to org context as discovery seed
        //    (not Targets — just store in a new org_discovered_networks table or return as data)
        // 3. SET session.status=CLAIMED, session.claimedByOrgId=orgId, session.claimedAt=now()
        // 4. Delete anon rows immediately (no 7-day retention after claim)
        // 5. orgAuditService.record(orgId, userId, "ANON_SCAN_CLAIMED", session.id)
    }

    @Transactional
    public void deleteSession(String rawViewToken) {
        // Soft-delete: SET status=DELETED, hard-purge handled by scheduler
    }
}
```

### Step 18: `AnonScanController.java`

```java
@RestController
@RequestMapping("/api/v1/anon")
public class AnonScanController {

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public AnonSessionCreateResponse createSession(HttpServletRequest request) {
        // Rate-limit check by X-Forwarded-For (in-memory ConcurrentHashMap<String,AtomicInteger>)
        // Do NOT store the IP
        return anonScanService.createSession(serverBaseUrl);
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> downloadAgent(
        @RequestParam String token,
        HttpServletResponse response
    ) {
        // Validate token, stamp it into agent ZIP, return as application/zip
        // Reuse AgentBundleService stamping pattern
    }

    /** Called by agent: poll for pending jobs. */
    @GetMapping("/jobs")
    public List<Object> getPendingJobs(HttpServletRequest request) {
        AnonScanSession session = (AnonScanSession) request.getAttribute("anonSession");
        return anonScanService.getPendingJobs(session);
    }

    /** Called by agent: push discovery results. */
    @PostMapping("/discovery-results")
    @ResponseStatus(HttpStatus.OK)
    public void submitDiscoveryResults(
        @RequestBody @Valid AnonDiscoveryResultsRequest req,
        HttpServletRequest request
    ) {
        AnonScanSession session = (AnonScanSession) request.getAttribute("anonSession");
        anonScanService.ingestDiscoveryResults(session, req);
    }

    /** Public read — viewToken in path, no auth header. */
    @GetMapping("/sessions/{viewToken}")
    public AnonSessionDashboardResponse getDashboard(@PathVariable String viewToken) {
        return anonScanService.getSessionForView(viewToken);
    }

    /** JWT-authenticated claim after signup. */
    @PostMapping("/sessions/{viewToken}/claim")
    @ResponseStatus(HttpStatus.OK)
    public void claimSession(
        @PathVariable String viewToken,
        @AuthenticationPrincipal CertGuardUserPrincipal principal
    ) {
        anonScanService.claimSession(viewToken, principal.getOrgId(), principal.getUserId());
    }

    /** GDPR erasure — no auth required, viewToken is the proof of ownership. */
    @DeleteMapping("/sessions/{viewToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable String viewToken) {
        anonScanService.deleteSession(viewToken);
    }
}
```

### Step 19: `AnonSessionPurgeScheduler.java`

Modelled on `CertificateExpiryScheduler` (see `server/.../service/CertificateExpiryScheduler.java`):

```java
@Component
public class AnonSessionPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnonSessionPurgeScheduler.class);

    // Inject AnonScanSessionRepository, AnonDiscoveredSubnetRepository, AnonDiscoveredDeviceRepository

    @Scheduled(cron = "0 0 3 * * *")   // 3am daily
    @SchedulerLock(name = "AnonSessionPurgeScheduler_purgeExpiredSessions",
                   lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    @Transactional
    public void purgeExpiredSessions() {
        // Hard-delete rows where view_expires_at < now()
        //   OR status IN ('CLAIMED', 'DELETED')
        // Cascade deletes anon_discovered_subnets + anon_discovered_devices
        // Log count of purged sessions
    }
}
```

---

## Part A — Agent changes

All agent work is in the agent module (plain Java 25, no Spring, no Lombok). Existing deps: Apache HttpClient 5, BouncyCastle, Jackson, Logback. Add **zero new dependencies**.

### Agent Step 1: `AgentMode.java`

New file `agent/.../config/AgentMode.java`:

```java
public enum AgentMode { AUTHENTICATED, ANONYMOUS }
```

Read from `application.properties` key `agent.mode` (default `AUTHENTICATED`). The personalised anonymous download stamps `agent.mode=ANONYMOUS` and `agent.anon.scan-token=<rawToken>` into `application.properties`.

In `AgentConfig` (wherever properties are loaded), expose:
```java
public AgentMode getMode();
public String getAnonScanToken();  // null in AUTHENTICATED mode
```

### Agent Step 2: `NicSubnetDiscovery.java`

New file `agent/.../discovery/NicSubnetDiscovery.java`:

```java
public class NicSubnetDiscovery {

    /**
     * Returns CIDRs for all directly-attached IPv4 subnets.
     * Filters: loopback, link-local (169.254.x.x), non-IPv4.
     */
    public List<SubnetInfo> discoverLocalSubnets() throws SocketException {
        return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
            .filter(ni -> { try { return ni.isUp() && !ni.isLoopback(); }
                            catch (SocketException e) { return false; } })
            .flatMap(ni -> ni.getInterfaceAddresses().stream())
            .filter(ia -> ia.getAddress() instanceof Inet4Address)
            .filter(ia -> !ia.getAddress().isLinkLocalAddress())
            .map(ia -> new SubnetInfo(
                toCidr(ia.getAddress(), ia.getNetworkPrefixLength()),
                ia.getNetworkInterface().getName()
            ))
            .distinct()
            .collect(Collectors.toList());
    }

    private String toCidr(InetAddress addr, short prefix) {
        // Mask addr to network address, append /prefix
        // e.g. 192.168.1.55/24 → "192.168.1.0/24"
    }

    public record SubnetInfo(String cidr, String ifaceName) {}
}
```

### Agent Step 3: `PortSweepScanner.java`

New file `agent/.../scanner/PortSweepScanner.java`:

```java
public class PortSweepScanner {

    // Common TLS ports
    public static final List<Integer> COMMON_TLS_PORTS = List.of(
        443, 8443, 9443, 993, 995, 465, 990, 636, 6443, 8883, 5671, 5061, 5986
    );

    public static final List<Integer> EXTENDED_PORTS = Stream.concat(
        COMMON_TLS_PORTS.stream(),
        Stream.of(80, 8080, 8008, 3000, 4000, 5000, 8000, 8888, 9000, 9090, 9200)
    ).collect(Collectors.toList());

    private final int connectTimeoutMs;
    private final int maxConcurrent;    // default 1000
    private final SslScanner sslScanner;

    /**
     * Sweeps all hosts in the CIDR across the given port list.
     * Uses virtual threads (Thread.ofVirtual()) and a Semaphore for fan-out control.
     * Returns one HostScanResult per reachable host with at least one open port.
     */
    public List<HostScanResult> sweep(String cidr, List<Integer> ports) throws Exception {
        List<InetAddress> hosts = expandCidr(cidr);
        Semaphore sem = new Semaphore(maxConcurrent);
        List<Future<HostScanResult>> futures = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (InetAddress host : hosts) {
                futures.add(executor.submit(() -> scanHost(host, ports, sem)));
            }
        }
        return futures.stream()
            .map(f -> { try { return f.get(); } catch (Exception e) { return null; } })
            .filter(Objects::nonNull)
            .filter(r -> !r.ports().isEmpty())
            .collect(Collectors.toList());
    }

    private HostScanResult scanHost(InetAddress host, List<Integer> ports,
                                     Semaphore sem) throws Exception {
        List<PortScanResult> results = new ArrayList<>();
        for (int port : ports) {
            sem.acquire();
            try {
                EndpointPortState state = tcpConnect(host, port);
                if (state == EndpointPortState.OPEN_TLS || state == EndpointPortState.OPEN_NO_TLS) {
                    X509Certificate[] chain = null;
                    Map<String, String> banners = Map.of();
                    DeviceClass deviceClass = DeviceClass.UNKNOWN;

                    if (state == EndpointPortState.OPEN_TLS) {
                        chain = sslScanner.probe(host.getHostAddress(), port);
                    }
                    banners = grabBanners(host, port, chain);
                    deviceClass = DeviceClassifier.classify(port, banners, chain);

                    results.add(new PortScanResult(port, state, chain, deviceClass, banners));
                }
            } finally {
                sem.release();
            }
        }
        return new HostScanResult(host.getHostAddress(), results);
    }

    private EndpointPortState tcpConnect(InetAddress host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            // Try TLS probe to distinguish OPEN_TLS vs OPEN_NO_TLS
            X509Certificate[] chain = sslScanner.probe(host.getHostAddress(), port);
            return chain != null ? EndpointPortState.OPEN_TLS : EndpointPortState.OPEN_NO_TLS;
        } catch (ConnectException | SocketTimeoutException e) {
            return EndpointPortState.CLOSED_OR_FILTERED;
        } catch (Exception e) {
            return EndpointPortState.CLOSED_OR_FILTERED;
        }
    }

    private List<InetAddress> expandCidr(String cidr) {
        // Parse network/prefix, iterate host addresses
    }

    public record HostScanResult(String ip, List<PortScanResult> ports) {}
    public record PortScanResult(
        int port, EndpointPortState state,
        X509Certificate[] chain, DeviceClass deviceClass,
        Map<String, String> banners
    ) {}
}
```

### Agent Step 4: Extend `SslScanner.java`

Add a new public method that bypasses the `targetId`-keyed serial cache (lines 42/60-74):

```java
/**
 * Probes host:port for TLS without any targetId serial cache.
 * Returns the peer certificate chain if TLS handshake succeeds, null otherwise.
 * Used by PortSweepScanner for discovered endpoints.
 */
public X509Certificate[] probe(String host, int port) {
    // Reuse fetchChainWithProtocols(host, port, ...) — the existing two-pass
    // BC JSSE + JVM TLS 1.3 logic at lines 226-253.
    // Catch SSLException → return null (OPEN_NO_TLS).
    // Catch IOException → return null (connection closed before handshake).
}
```

### Agent Step 5: `DeviceClassifier.java`

New file `agent/.../scanner/DeviceClassifier.java`:

```java
public class DeviceClassifier {

    public static DeviceClass classify(int port, Map<String, String> banners,
                                        X509Certificate[] chain) {
        String httpTitle  = banners.getOrDefault("http_title", "").toLowerCase();
        String httpServer = banners.getOrDefault("http_server", "").toLowerCase();
        String sshVersion = banners.getOrDefault("ssh_version", "").toLowerCase();
        String tlsCn      = banners.getOrDefault("tls_cn", "").toLowerCase();

        // ROUTER signals
        if (port == 161 || port == 830 || port == 23)            return DeviceClass.ROUTER;
        if (containsAny(httpTitle, "routeros","openwrt","pfsense",
                        "cisco","junos","mikrotik","fortigate")) return DeviceClass.ROUTER;
        if (containsAny(sshVersion, "routeros","cisco","junos",
                        "arista","hpe","procurve"))              return DeviceClass.ROUTER;

        // SWITCH signals (subset of router indicators without gateway ports)
        if (containsAny(httpTitle, "procurve","ex series","catalyst",
                        "nexus","summit","comware"))             return DeviceClass.SWITCH;

        // SERVER signals
        if (containsAny(httpServer, "nginx","apache","iis","lighttpd","caddy","tomcat"))
                                                                 return DeviceClass.SERVER;
        if (port == 22 || port == 80 || port == 443 || port == 8080 || port == 8443)
                                                                 return DeviceClass.SERVER;

        // WORKSTATION signals
        if (port == 3389 || port == 5900)                        return DeviceClass.WORKSTATION;

        return DeviceClass.UNKNOWN;
    }

    /** Grabs HTTP title+Server header and SSH version string. */
    public static Map<String, String> grabBanners(InetAddress host, int port,
                                                   X509Certificate[] chain) {
        Map<String, String> banners = new HashMap<>();

        // SSH: read first line from port 22 (plaintext SSH-2.0-... banner)
        if (port == 22) {
            try (Socket s = new Socket(host, port);
                 BufferedReader r = new BufferedReader(
                     new InputStreamReader(s.getInputStream()))) {
                s.setSoTimeout(2000);
                String line = r.readLine();
                if (line != null && line.startsWith("SSH-")) banners.put("ssh_version", line.trim());
            } catch (Exception ignored) {}
        }

        // HTTP: GET / with a short timeout, read Server: and <title>
        if (port == 80 || port == 8080 || port == 8008) {
            // Use Apache HttpClient 5 (already a dep) with 2s connect+read timeout
            // Extract response header "Server" and first <title>...</title>
        }

        // TLS CN/O from chain[0]
        if (chain != null && chain.length > 0) {
            X500Principal subject = chain[0].getSubjectX500Principal();
            // Parse CN= and O= from subject DN
            banners.put("tls_cn", extractCn(subject));
            banners.put("tls_o",  extractO(subject));
        }

        return banners;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }
}
```

### Agent Step 6: Extend `ScanJob.java`

Add the `jobType` discriminator and a nested payload class:

```java
public class ScanJob {
    // Existing fields: jobId, targetId, host, port, lastKnownSerialHash, lastCertificateId
    // ... unchanged ...

    // New field — absent/null means CERTIFICATE_SCAN (backward compatible)
    private String jobType;               // "CERTIFICATE_SCAN" | "NETWORK_SCAN" | "DISCOVERY"

    // Populated only when jobType == "NETWORK_SCAN"
    private NetworkScanPayload networkScan;

    public static class NetworkScanPayload {
        private UUID networkScanId;
        private String cidr;
        private String portProfile;   // "COMMON_TLS" | "EXTENDED" | "FULL" | "CUSTOM"
        private List<Integer> customPorts;
        private int connectTimeoutMs;
        private int tlsTimeoutMs;
        // Jackson @JsonIgnoreProperties(ignoreUnknown=true) on this class
    }
}
```

### Agent Step 7: Extend `PollLoop.java`

Route by `jobType` in the scan dispatch block:

```java
private void processScanJob(ScanJob job) {
    String type = job.getJobType();
    if ("NETWORK_SCAN".equals(type)) {
        processNetworkScanJob(job);
    } else if ("DISCOVERY".equals(type) && config.getMode() == AgentMode.ANONYMOUS) {
        processDiscoveryJob(job);
    } else {
        // Existing CERTIFICATE_SCAN path — unchanged
        processCertificateScanJob(job);
    }
}
```

**One-scan rule for anonymous mode:**

```java
private void processDiscoveryJob(ScanJob job) {
    NicSubnetDiscovery discovery = new NicSubnetDiscovery();
    List<NicSubnetDiscovery.SubnetInfo> subnets = discovery.discoverLocalSubnets();

    // For each subnet, run PortSweepScanner with COMMON_TLS profile
    PortSweepScanner sweeper = new PortSweepScanner(connectTimeoutMs, maxConcurrent, sslScanner);
    List<AnonDeviceDto> devices = new ArrayList<>();
    for (NicSubnetDiscovery.SubnetInfo subnet : subnets) {
        List<PortSweepScanner.HostScanResult> hosts = sweeper.sweep(subnet.cidr(), COMMON_TLS_PORTS);
        for (var host : hosts) {
            devices.add(toAnonDeviceDto(subnet.cidr(), host));
        }
    }

    // POST results to /api/v1/anon/discovery-results
    serverApiClient.submitAnonDiscoveryResults(subnets, devices);

    // After successful submit: signal the poll loop to stop
    // (the server will return [] on the next /api/v1/anon/jobs poll)
}
```

### Agent Step 8: Extend `ServerApiClient.java`

Add new methods:

```java
// Authenticated: batch network scan results
public void submitNetworkResults(AgentNetworkResultsBatch batch) throws IOException {
    // POST /api/v1/agent/network-results with X-Agent-Id + X-Agent-Key headers
    // Compute HMAC over "{networkScanId}:{chunkIndex}:{hostCount}:{timestamp}"
}

// Anonymous: poll for jobs
public List<ScanJob> getAnonJobs() throws IOException {
    // GET /api/v1/anon/jobs with X-Anon-Scan-Token header
}

// Anonymous: push discovery results
public void submitAnonDiscoveryResults(
    List<NicSubnetDiscovery.SubnetInfo> subnets,
    List<AnonDeviceDto> devices) throws IOException {
    // POST /api/v1/anon/discovery-results with X-Anon-Scan-Token header
}
```

---

## Implementation sequence

Build in this order (each step unblocks the next):

1. **V10 + V11 migrations** — tables exist before any Java code runs
2. **Server enums** — `NetworkScanStatus`, `PortScanProfile`, `EndpointPortState`, `DeviceClass`, `AnonSessionStatus`
3. **JPA entities** — `NetworkScan`, `DiscoveredEndpoint`, `AnonScanSession`, `AnonDiscoveredSubnet`, `AnonDiscoveredDevice`
4. **Repositories** — one per entity
5. **DTOs** — all request/response records
6. **`NetworkScanService`** — `createScan`, `listScans`, `getScan` (no ingest yet)
7. **`NetworkScanController`** — CRUD endpoints (Part A read/write, minus ingest)
8. **Agent: `SslScanner.probe()`** — extract the cache-bypass handshake method
9. **Agent: `PortSweepScanner`** — TCP connect sweep + TLS probe
10. **Agent: `DeviceClassifier` + `grabBanners()`**
11. **Agent: `ScanJob` jobType discriminator + `PollLoop` routing**
12. **Agent: `ServerApiClient.submitNetworkResults()`**
13. **`NetworkScanService.ingestBatchResults()`** — chain/revocation wiring (requires steps 8–12)
14. **Agent batch result endpoint on server** — add to `AgentController`
15. **`AnonScanAuthFilter`** — token resolution
16. **`SecurityConfig` changes** — register filter + permit anon paths
17. **`AnonScanService`** — all methods
18. **`AnonScanController`** — all endpoints
19. **`AnonSessionPurgeScheduler`**
20. **Agent: `NicSubnetDiscovery`**, `AgentMode`, anon poll loop, `submitAnonDiscoveryResults`

Part A (steps 1–14) can be reviewed and merged independently of Part B (steps 15–20).
