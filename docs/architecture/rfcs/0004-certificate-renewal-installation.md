# RFC 0004 — Certificate Renewal & Installation

- **Status:** Accepted (open questions resolved 2026-05-29)
- **Authors:** CertGuard Architect
- **Relates to:** HLD §2/§3, LLD §2/§3/§5/§6, GAPS R9

This RFC covers the 8-point Certificate Renewal & Installation requirement. All eight open questions are resolved (see §6). The two structurally significant confirmations are: **agent generates the CSR locally (private keys never transit the server)** and **delivery jobs ride a separate agent endpoint (B2)** with a generic durable `agent_jobs` queue.

Grounding files (backend/frontend implement against these):
- `server/src/main/java/com/certguard/service/AgentService.java`
- `server/src/main/java/com/certguard/controller/AgentController.java`
- `server/src/main/java/com/certguard/service/NotificationService.java`
- `server/src/main/java/com/certguard/scheduler/CertificateExpiryScheduler.java`
- `server/src/main/java/com/certguard/service/AgentBundleService.java`
- `agent/src/main/java/com/certguard/agent/http/PollLoop.java`
- `agent/src/main/java/com/certguard/agent/http/ServerApiClient.java`
- `agent/src/main/java/com/certguard/agent/config/AgentConfig.java`

---

## 0. Resolved Decisions (changelog from draft)

| Q | Decision |
|---|---|
| **Q8 Private keys** | Agent generates keypair + CSR **locally**. Private key never leaves the customer network and never transits the server. Server forwards the CSR to the CA and stores/relays only the signed public cert + chain. `certificate_renewal_requests` gains `csr_pem TEXT`. `CaRenewalRequest` carries `csrPem`; the server never generates keypairs. **Sensitive-data-at-rest risk is eliminated** — packages are public material only, so no at-rest encryption is required. |
| **Q4 Agent endpoint** | Separate `GET /api/v1/agent/delivery-jobs` (B2). The existing `GET /api/v1/agent/jobs` (scan) is **untouched** — fully backward compatible with deployed agents. |
| **Q2 Install mechanism** | Two-tier model. **Tier 1**: Java `java.nio.file` atomic local install (always runs). **Tier 2**: optional configurable deploy hook (certbot/acme.sh pattern) — agent executes a fixed configured script path, passing cert metadata as environment variables only, never constructing shell from user input. |
| Q1, Q3, Q5, Q6, Q7 | See §6. |

---

## 1. High-Level Design

### 1.1 Scope

Two loosely-coupled capabilities:

1. **Local-CSR renewal → CA → store public cert → durable deliver → install → notify** (requirements 3, 4, 5, 6, 7, 8).
2. **Smarter expiry emails** — agent-discovered targets get a deep link to the cert detail page; non-agent targets get the static "use the agent" note (requirements 1, 2).

### 1.2 Why a generic `agent_jobs` table (not RabbitMQ, not overloading scan jobs)

Requirement #6 demands durable, offline-safe delivery of a *new* job type. The existing `agent_scan_jobs` is scan-specific.

| Option | Verdict |
|---|---|
| Overload `agent_scan_jobs` with `job_type` + nullable payload | Rejected — pollutes scan semantics, forces nullable columns, couples two lifecycles. |
| **New generic `agent_jobs` table (chosen)** | Clean separation; reuses the proven `FOR UPDATE SKIP LOCKED` claim; room for future job types. The DB row *is* the durable queue — survives agent downtime exactly like scan jobs do today. |
| RabbitMQ (GAPS R9) | Rejected for this path — the agent is pull-based over HTTPS on a customer LAN and cannot be an AMQP consumer. A server-side broker adds no durability the DB does not already provide. |

### 1.3 Durable delivery (design statement)

- Durable queue = the `agent_jobs` table. A `PENDING` row persists until an agent connects and claims it (req #6 "picks it up next time it connects").
- **At-least-once**: claim (`PENDING→CLAIMED`) → download → install → report. If the agent dies mid-install, a stale-claim sweep (mirroring `AgentService.resetStaleClaimedJobs`) returns the row to `PENDING`. Installs are idempotent (checksum-gated; identical content = no-op).
- **Dedup**: at most one active delivery job per `(targetId, packageId)` via a partial unique index on `dedup_key`.
- **Bounded retries**: `attempt_count` increments per claim; after `app.agent.job.max-attempts` (default 5) the job goes terminal `FAILED` and the user is notified.

### 1.4 Renewal lifecycle — two durable agent jobs per renewal

A single renewal request produces **two** sequential durable agent jobs:

1. `CERT_RENEW_CSR` — agent generates keypair + CSR locally, submits the CSR PEM to the server.
2. `CERT_DELIVERY` — after the CA signs the cert, server queues this; agent downloads the public cert package and installs it.

Both jobs are rows in `agent_jobs` and survive agent downtime.

---

## 2. Key Flows

### 2.1 Flow 1 — Renewal request through CSR and CA (local-CSR model)

```
UI → POST /renewals
  → CertificateRenewalService.requestRenewal()
  → insert renewal (status=REQUESTED)
  → AgentJobService.enqueueCsrJob()   → agent_jobs: CERT_RENEW_CSR, PENDING
  → renewal status=CSR_PENDING
  → 202 RenewalResponse

[Agent polls, picks up CERT_RENEW_CSR]
  → CsrGenerator.generate(cn, sans)   → keypair on disk (private key stays on host)
  → POST /api/v1/agent/jobs/{jobId}/csr  {csrPem}
  → CertificateRenewalService.submitCsr()
  → renewal: csr_pem stored, status=CSR_RECEIVED
  → driveCaAsync()  [@Async]
    → CaProvider.requestCertificate(CaRenewalRequest{csrPem})   [stub: CaNotConfiguredException today]
    → CertificatePackageStore.store(public cert+chain)          [public material only, Q8]
    → renewal status=STORED
    → AgentJobService.enqueueCertDelivery()  → agent_jobs: CERT_DELIVERY, PENDING
    → renewal status=DELIVERY_QUEUED
    → NotificationService.dispatchRenewalReady()               [download link, no key material]
```

### 2.2 Flow 2 — Agent installs delivered package (two-tier, offline-safe)

```
[Agent polls, picks up CERT_DELIVERY]
  → GET /api/v1/agent/delivery-jobs
  → AgentJobService.claimDeliveryJobs()  (FOR UPDATE SKIP LOCKED, attempt_count++)

  → GET /api/v1/agent/jobs/{jobId}/package
  → CertificatePackageStore.openStream()  (verify agent owns job)
  → bytes + X-Checksum-SHA256

  → CertInstaller.install(bytes, targetLocation, expectedSha256)
    [Tier 1] sha256 verify → temp file → Files.move(ATOMIC_MOVE) → POSIX/ACL permissions
    [Tier 2] if deploy-hook configured: exec fixed path; env CERT_PATH/CERT_DOMAIN/RENEWAL_ID; timeout

  success:
    → POST /jobs/{jobId}/report {status=SUCCESS, sha256, bytesWritten}
    → agent_jobs: COMPLETED; renewal: DELIVERED
    → NotificationService.dispatchRenewalInstalled()

  failure (AccessDeniedException → WRITE_PERMISSION_DENIED, hook exit → HOOK_FAILED):  [req #8]
    → POST /jobs/{jobId}/report {status=FAILED, errorCode, errorDetail}
    → agent_jobs: FAILED; renewal: FAILED
    → NotificationService.dispatchRenewalFailed(user, errorDetail)   [surfaces agent error to user]
```

### 2.3 Flow 3 — Expiry email with conditional deep link (req #1, #2)

```
CertificateExpiryScheduler → NotificationService.dispatchExpiryAlert(certificateRecord, daysLeft, severity)

  if record.target.agent != null:   [agent-discovered]
    ctx.agentDiscovered = true
    ctx.deepLinkUrl = uiBaseUrl + "/certificates/" + record.id
    → email: deep-link button to cert detail page     [req #1]

  else:                             [not agent-discovered]
    ctx.agentDiscovered = false
    → email: static note "For automatic renewal please use the agent and configure it."  [req #2]
```

---

## 3. Low-Level Design

### 3.1 Flyway migrations (latest existing: `V26`)

**`V27__agent_jobs.sql`**

```sql
CREATE TYPE agent_job_type   AS ENUM ('CERT_RENEW_CSR','CERT_DELIVERY');
CREATE TYPE agent_job_status AS ENUM ('PENDING','CLAIMED','COMPLETED','FAILED','CANCELLED');

CREATE TABLE agent_jobs (
    id            UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id      UUID             NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    org_id        UUID             NOT NULL,
    target_id     UUID             REFERENCES targets(id) ON DELETE SET NULL,
    renewal_id    UUID,                                     -- FK added in V28
    job_type      agent_job_type   NOT NULL,
    status        agent_job_status NOT NULL DEFAULT 'PENDING',
    payload       JSONB            NOT NULL DEFAULT '{}',
    dedup_key     VARCHAR(200),
    attempt_count INT              NOT NULL DEFAULT 0,
    error_code    VARCHAR(64),
    error_detail  TEXT,                                     -- req #8: detailed agent error
    claimed_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_jobs_agent_status ON agent_jobs(agent_id, status);
CREATE INDEX idx_agent_jobs_org_id       ON agent_jobs(org_id);
CREATE INDEX idx_agent_jobs_status       ON agent_jobs(status);
CREATE UNIQUE INDEX uq_agent_jobs_dedup_active
    ON agent_jobs(dedup_key) WHERE status IN ('PENDING','CLAIMED');
```

**`V28__certificate_renewals.sql`**

```sql
CREATE TYPE renewal_status AS ENUM (
    'REQUESTED','CSR_PENDING','CSR_RECEIVED','CA_PENDING','CA_ISSUED',
    'STORED','DELIVERY_QUEUED','DELIVERED','FAILED','CANCELLED'
);
CREATE TYPE ca_provider_type AS ENUM ('NONE','LETS_ENCRYPT','DIGICERT','SECTIGO','INTERNAL');

CREATE TABLE certificate_renewal_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    certificate_id      UUID NOT NULL REFERENCES certificate_records(id) ON DELETE CASCADE,
    target_id           UUID NOT NULL REFERENCES targets(id) ON DELETE CASCADE,
    agent_id            UUID REFERENCES agents(id) ON DELETE SET NULL,
    status              renewal_status   NOT NULL DEFAULT 'REQUESTED',
    ca_provider         ca_provider_type NOT NULL DEFAULT 'NONE',
    ca_external_ref     VARCHAR(256),
    csr_pem             TEXT,              -- Q8: CSR generated locally by the agent; held for the CA call
    requested_by        UUID NOT NULL,     -- gateway X-CG-User-Id
    target_install_path VARCHAR(1024),
    package_id          UUID,             -- FK below, set once stored
    csr_job_id          UUID REFERENCES agent_jobs(id) ON DELETE SET NULL,
    delivery_job_id     UUID REFERENCES agent_jobs(id) ON DELETE SET NULL,
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Public material only (Q8): leaf + chain, never a private key.
CREATE TABLE certificate_packages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    renewal_id          UUID NOT NULL REFERENCES certificate_renewal_requests(id) ON DELETE CASCADE,
    storage_path        VARCHAR(1024) NOT NULL,
    file_name           VARCHAR(256)  NOT NULL,
    content_type        VARCHAR(128)  NOT NULL DEFAULT 'application/x-pem-file',
    size_bytes          BIGINT        NOT NULL,
    checksum_sha256     VARCHAR(64)   NOT NULL,
    download_token_hash VARCHAR(64),
    downloaded_at       TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE certificate_renewal_requests
    ADD CONSTRAINT fk_renewal_package
    FOREIGN KEY (package_id) REFERENCES certificate_packages(id) ON DELETE SET NULL;

ALTER TABLE agent_jobs
    ADD CONSTRAINT fk_agent_jobs_renewal
    FOREIGN KEY (renewal_id) REFERENCES certificate_renewal_requests(id) ON DELETE SET NULL;

CREATE INDEX idx_renewal_org_id  ON certificate_renewal_requests(org_id);
CREATE INDEX idx_renewal_cert_id ON certificate_renewal_requests(certificate_id);
CREATE INDEX idx_renewal_status  ON certificate_renewal_requests(status);
CREATE INDEX idx_pkg_renewal_id  ON certificate_packages(renewal_id);
CREATE INDEX idx_pkg_org_id      ON certificate_packages(org_id);
```

Convention compliance: UUID PKs via `gen_random_uuid()`, `created_at`/`updated_at` triggers, denormalized `org_id`, Postgres `CREATE TYPE ... AS ENUM` mapped via `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`, `ddl-auto: none` — all tables come from migrations.

### 3.2 REST Endpoints

Auth: **J** = JWT/gateway `X-CG-*`; **A** = agent `X-Agent-Id`/`X-Agent-Key` (`AgentAuthFilter`).

| Method | Path | Auth | Request | Response | Errors |
|---|---|---|---|---|---|
| POST | `/api/v1/certificates/{certId}/renewals` | J (ADMIN/ENGINEER) | `RequestRenewalRequest{caProvider?, targetInstallPath?}` | 202 `RenewalResponse{status=CSR_PENDING}` | 404 cert, 409 in-progress, 422 not-agent-discovered, 403 |
| GET | `/api/v1/certificates/{certId}/renewals` | J | — | `List<RenewalResponse>` | 404 |
| GET | `/api/v1/renewals/{renewalId}` | J | — | `RenewalResponse` (incl. `failureReason`) | 404 |
| POST | `/api/v1/renewals/{renewalId}/cancel` | J (ADMIN/ENGINEER) | — | 200 `RenewalResponse` | 404, 409 terminal |
| GET | `/api/v1/renewals/{renewalId}/package` | J | — | `application/x-pem-file` (user download, req #5) | 404, 410 expired |
| GET | `/api/v1/agent/delivery-jobs` | A | — | `List<DeliveryJobResponse>` (B2 — scan endpoint untouched) | 401 |
| POST | `/api/v1/agent/jobs/{jobId}/csr` | A | `AgentCsrSubmitRequest{csrPem}` | 200 | 401, 403 not-owner, 404, 409 wrong-state |
| GET | `/api/v1/agent/jobs/{jobId}/package` | A | — | `application/x-pem-file` + `X-Checksum-SHA256` (req #7) | 401, 403, 404, 410 consumed |
| POST | `/api/v1/agent/jobs/{jobId}/report` | A | `AgentJobReportRequest{status, errorCode?, errorDetail?, checksumSha256?, bytesWritten?}` | 200 | 401, 403, 404 |

`GET /api/v1/agent/jobs` (scan) is **explicitly unchanged** — still returns `List<ScanJobResponse>` array parsed by `ServerApiClient.pollJobs` today. New mappings live under the existing `@RequestMapping("/api/v1/agent")` and must stay relative (prefix-concat warning at `AgentController.java:39-42`).

### 3.3 Service Class Skeletons (server)

#### `CaProvider` interface and stub

```java
public interface CaProvider {
    CaProviderType type();
    /** Accepts a CSR PEM generated locally by the agent. Stub throws CaNotConfiguredException. */
    CaIssuedPackage requestCertificate(CaRenewalRequest request) throws CaProviderException;
    CaIssuedPackage pollOrder(String externalRef) throws CaProviderException;
}

// CaRenewalRequest { String csrPem; String commonName; List<String> sans; UUID orgId; int validityDays; }
// CaIssuedPackage  { String fileName; String contentType; byte[] bytes; String leafPem; String chainPem; String externalRef; }
//   -- public material only (Q8); no private key.

@Component
@ConditionalOnProperty(name = "app.renewal.ca.provider", havingValue = "NONE", matchIfMissing = true)
public class NoopCaProvider implements CaProvider {
    @Override public CaProviderType type() { return CaProviderType.NONE; }
    @Override public CaIssuedPackage requestCertificate(CaRenewalRequest r) {
        throw new CaNotConfiguredException(
            "No CA provider configured. Set app.renewal.ca.provider and supply credentials.");
    }
    @Override public CaIssuedPackage pollOrder(String ref) {
        throw new CaNotConfiguredException("No CA provider configured.");
    }
}
```

Wire future implementations (e.g. `LetsEncryptCaProvider`) via `@ConditionalOnProperty("app.renewal.ca.provider")`, mirroring `AgentBundleService.fetchAgentJarBytes` fallback pattern.

#### `CertificateRenewalService` (orchestrator)

```java
@Service @Transactional(readOnly = true) @RequiredArgsConstructor
public class CertificateRenewalService {
    private final CertificateRecordRepository certRepository;
    private final CertificateRenewalRequestRepository renewalRepository;
    private final CertificatePackageStore packageStore;
    private final CertificatePackageRepository packageRepository;
    private final AgentJobService agentJobService;
    private final NotificationService notificationService;
    private final CaProvider caProvider;
    private final SubscriptionGuard subscriptionGuard;

    @Transactional
    public RenewalResponse requestRenewal(UUID orgId, UUID certId, UUID userId,
                                          RequestRenewalRequest req) {
        subscriptionGuard.assertScansAllowed(orgId);
        CertificateRecord cert = certRepository.findByIdAndOrgId(certId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate not found"));
        Target target = cert.getTarget();
        if (target.getAgent() == null)
            throw new RenewalNotSupportedException("Automatic renewal requires an agent-managed target.");
        if (renewalRepository.existsByCertificateIdAndStatusIn(certId, ACTIVE_STATES))
            throw new IllegalStateException("A renewal is already in progress for this certificate");

        CertificateRenewalRequest r = renewalRepository.save(/* ... builder ... */);
        AgentJob csrJob = agentJobService.enqueueCsrJob(r, cert);
        r.setCsrJobId(csrJob.getId());
        r.setStatus(RenewalStatus.CSR_PENDING);
        return RenewalResponse.from(r);
    }

    @Transactional
    public void submitCsr(Agent agent, UUID jobId, String csrPem) {
        CertificateRenewalRequest r = renewalRepository.findByCsrJobId(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Renewal not found for job"));
        assertAgentOwnsRenewal(agent, r);
        r.setCsrPem(csrPem);
        r.setStatus(RenewalStatus.CSR_RECEIVED);
        agentJobService.completeJob(jobId, agent, AgentJobReportRequest.success());
        driveCaAsync(r.getId());
    }

    @Async
    public void driveCaAsync(UUID renewalId) {
        // REQUIRES_NEW tx; set CA_PENDING
        // try {
        //   pkg = caProvider.requestCertificate(new CaRenewalRequest(r.getCsrPem(), ...));
        //   stored = packageStore.store(r.getOrgId(), r.getId(), pkg);       // public only, req #4
        //   r.setPackageId(stored.getId()); r.setStatus(STORED);
        //   job = agentJobService.enqueueCertDelivery(r, stored);            // req #6
        //   r.setDeliveryJobId(job.getId()); r.setStatus(DELIVERY_QUEUED);
        //   notificationService.dispatchRenewalReady(r, stored);             // req #5, download link only
        // } catch (CaNotConfiguredException e) {
        //   r.setStatus(FAILED); r.setFailureReason("CA integration not configured (stub)");
        // }
    }

    @Transactional public RenewalResponse cancel(UUID orgId, UUID renewalId) { /* terminal-state guard + cancel jobs */ }
    public List<RenewalResponse> listForCertificate(UUID orgId, UUID certId) { /* ... */ }
    public RenewalResponse get(UUID orgId, UUID renewalId) { /* ... */ }

    private static final List<RenewalStatus> ACTIVE_STATES = List.of(
        RenewalStatus.REQUESTED, RenewalStatus.CSR_PENDING, RenewalStatus.CSR_RECEIVED,
        RenewalStatus.CA_PENDING, RenewalStatus.CA_ISSUED, RenewalStatus.STORED,
        RenewalStatus.DELIVERY_QUEUED);
}
```

#### `CertificatePackageStore`

```java
@Service
public class CertificatePackageStore {
    @Value("${app.renewal.storage-path:/opt/certguard/renewals}") private String storageRoot;

    /** Writes <root>/<orgId>/<renewalId>/<fileName>; returns persisted metadata + sha256. */
    public CertificatePackage store(UUID orgId, UUID renewalId, CaIssuedPackage pkg) { /* ... */ }
    /** Streams bytes for agent/user download; verifies caller scope before opening. */
    public InputStream openStream(CertificatePackage pkg) { /* ... */ }
    public String sha256Hex(byte[] data) { /* same helper style as AgentBundleService.sha256Hex */ }
}
```

#### `AgentJobService`

```java
@Service @Transactional(readOnly = true) @RequiredArgsConstructor
public class AgentJobService {
    private final AgentJobRepository agentJobRepository;
    private final NotificationService notificationService;
    private final CertificateRenewalRequestRepository renewalRepository;

    @Transactional public AgentJob enqueueCsrJob(CertificateRenewalRequest r, CertificateRecord cert) {
        String dedup = "CERT_RENEW_CSR:" + r.getId();
        return upsertActive(dedup, AgentJobType.CERT_RENEW_CSR, r,
            Map.of("commonName", cert.getCommonName(), "sans", cert.getSubjectAltNames()));
    }

    @Transactional public AgentJob enqueueCertDelivery(CertificateRenewalRequest r, CertificatePackage pkg) {
        String dedup = "CERT_DELIVERY:" + r.getTargetId() + ":" + pkg.getId();
        return upsertActive(dedup, AgentJobType.CERT_DELIVERY, r,
            Map.of("packageId", pkg.getId().toString(),
                   "targetLocation", r.getTargetInstallPath(),
                   "checksumSha256", pkg.getChecksumSha256(),
                   "fileName", pkg.getFileName()));
    }

    /** FOR UPDATE SKIP LOCKED — same pattern as AgentScanJobRepository.claimPendingJobsWithLock. */
    @Transactional public List<DeliveryJobResponse> claimDeliveryJobs(Agent agent) { /* PENDING→CLAIMED, attempt_count++ */ }

    @Transactional public void completeJob(UUID jobId, Agent agent, AgentJobReportRequest rep) { /* COMPLETED; cascade renewal state */ }

    @Transactional public void failJob(UUID jobId, Agent agent, AgentJobReportRequest rep) {
        // FAILED + error_code/error_detail (req #8); cascade renewal→FAILED
        // notificationService.dispatchRenewalFailed(renewal, rep.errorDetail());
    }

    /** Mirrors AgentService.resetStaleClaimedJobs — REQUIRED, not optional. */
    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "AgentJobService_resetStaleClaimedJobs", lockAtMostFor = "PT5M", lockAtLeastFor = "PT4M")
    @Transactional public void resetStaleClaimedJobs() {
        // CLAIMED older than stale-claim-minutes → PENDING; attempt_count>=max-attempts → terminal FAILED + notify
    }
}
```

#### `NotificationService` extensions

Reuse `sendMimeEmail` + dev-mode skip (`NotificationService.java:200-218`):

- `dispatchExpiryAlert(CertificateRecord, int daysLeft, String severity)` — **signature widened** from `(Target, ...)` to carry the cert id. Sets `agentDiscovered = record.getTarget().getAgent() != null` and `deepLinkUrl = uiBaseUrl + "/certificates/" + record.getId()`.
- `dispatchRenewalReady(CertificateRenewalRequest, CertificatePackage)` → template `renewal-ready` (download link to `/renewals/{id}/package` — no key material, Q8).
- `dispatchRenewalInstalled(CertificateRenewalRequest)` → template `renewal-installed`.
- `dispatchRenewalFailed(CertificateRenewalRequest, String errorDetail)` → template `renewal-failed` (surfaces agent error, req #8).

### 3.4 Agent-side additions (framework-free; BouncyCastle already present)

#### New model `DeliveryJob`

```java
// agent/.../model/DeliveryJob.java
public class DeliveryJob {
    private String jobId, targetId, jobType;       // jobType: CERT_RENEW_CSR | CERT_DELIVERY
    private String packageId, targetLocation, checksumSha256, fileName;
    private String commonName;                      // for CSR jobs
    private java.util.List<String> sans;            // for CSR jobs
    // hand-written getters/setters (ScanJob.java style)
}
```

#### `CsrGenerator` (new — Q8)

```java
// agent/.../security/CsrGenerator.java
public class CsrGenerator {
    /** Generates RSA-2048 (or EC P-256) keypair locally. Writes private key to disk (0600). Returns ONLY the CSR PEM. */
    public CsrResult generate(String commonName, java.util.List<String> sans, String keyDir) {
        // BouncyCastle: KeyPairGenerator + PKCS10CertificationRequestBuilder
        // persist private key to keyDir/<cn>.key (never transmitted)
        // return CsrResult { String csrPem; java.nio.file.Path privateKeyPath; }
    }
}
```

#### `CertInstaller` (new — two-tier, Q2)

```java
// agent/.../installer/CertInstaller.java
public class CertInstaller {
    private final AgentConfig config;

    public InstallOutcome install(byte[] cert, String targetLocation, String expectedSha256) {
        // 0. sha256(cert) == expectedSha256 else fail("CHECKSUM_MISMATCH", ...)

        // --- Tier 1: java.nio atomic local write (always) ---
        try {
            Path target = Paths.get(targetLocation);
            Path tmp = Files.createTempFile(target.getParent(), ".cg-cert-", ".tmp");
            Files.write(tmp, cert);
            // Linux/macOS: PosixFileAttributeView → rw-r--r-- (or as configured)
            // Windows: AclFileAttributeView
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AccessDeniedException e) {
            return InstallOutcome.failure("WRITE_PERMISSION_DENIED", e.getMessage());   // req #8
        } catch (IOException e) {
            return InstallOutcome.failure("WRITE_IO_ERROR", e.getMessage());
        }

        // --- Tier 2: optional deploy hook (certbot/acme.sh pattern) ---
        String hook = config.deployHook();   // certguard.install.deploy-hook; empty = Tier 1 only
        if (hook != null && !hook.isBlank()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(hook);   // FIXED configured path only — no shell interpolation
                pb.environment().put("CERT_PATH",   targetLocation);
                pb.environment().put("CERT_DOMAIN", deriveDomain(targetLocation));
                pb.environment().put("RENEWAL_ID",  config.currentRenewalId());
                pb.redirectErrorStream(false);
                Process p = pb.start();
                boolean done = p.waitFor(config.deployHookTimeoutSeconds(), TimeUnit.SECONDS);
                if (!done) { p.destroyForcibly(); return InstallOutcome.failure("HOOK_FAILED", "deploy hook timed out"); }
                if (p.exitValue() != 0) return InstallOutcome.failure("HOOK_FAILED", readStderr(p));  // req #8
            } catch (Exception e) {
                return InstallOutcome.failure("HOOK_FAILED", e.getMessage());
            }
        }
        return InstallOutcome.success(expectedSha256, cert.length);
    }
}
// InstallOutcome { boolean success; String errorCode; String detail; String checksum; long bytesWritten; }
```

#### `ServerApiClient` additions

```java
// Reuse addAgentHeaders (ServerApiClient.java:177-180); first binary-download path in the client.
public List<DeliveryJob> pollDeliveryJobs();                             // GET /api/v1/agent/delivery-jobs
public void submitCsr(String jobId, String csrPem);                      // POST /api/v1/agent/jobs/{jobId}/csr
public byte[] downloadPackage(String jobId);                             // GET /api/v1/agent/jobs/{jobId}/package; verify X-Checksum-SHA256
public void reportJob(String jobId, String status, String errorCode,
                      String errorDetail, String sha256, long bytesWritten);  // POST /api/v1/agent/jobs/{jobId}/report
```

#### `PollLoop.tick()` extension (~`PollLoop.java:97`)

```java
// 5. Poll & process delivery/CSR jobs — isolated; a failure here must not abort scan processing.
try {
    for (DeliveryJob dj : api.pollDeliveryJobs()) {
        try {
            if ("CERT_RENEW_CSR".equals(dj.getJobType())) {
                CsrResult csr = csrGenerator.generate(dj.getCommonName(), dj.getSans(), config.privateKeyDir());
                api.submitCsr(dj.getJobId(), csr.getCsrPem());   // private key stays on host (Q8)
            } else {  // CERT_DELIVERY
                byte[] pkg = api.downloadPackage(dj.getJobId());
                InstallOutcome out = installer.install(pkg, dj.getTargetLocation(), dj.getChecksumSha256());
                if (out.isSuccess())
                    api.reportJob(dj.getJobId(), "SUCCESS", null, null, out.getChecksum(), out.getBytesWritten());
                else
                    api.reportJob(dj.getJobId(), "FAILED", out.getErrorCode(), out.getDetail(), null, 0);  // req #8
            }
        } catch (Exception e) {
            api.reportJob(dj.getJobId(), "FAILED", "AGENT_EXCEPTION", e.getMessage(), null, 0);
        }
    }
} catch (Exception e) {
    log.warn("Delivery-job poll failed: {}", e.getMessage());
}
```

### 3.5 Error model additions (RFC 9457)

Follows the `SubscriptionSuspendedException` typed-URI pattern at `GlobalExceptionHandler.java:53-59`.

| Exception | HTTP | `type` URI | `title` |
|---|---|---|---|
| `RenewalNotSupportedException` | 422 | `https://certguard.dev/problems/renewal-not-supported` | "Renewal Not Supported" |
| `CaNotConfiguredException` | 503 | `https://certguard.dev/problems/ca-not-configured` | "CA Provider Not Configured" |
| `PackageNotFoundException` (extends `ResourceNotFoundException`) | 404 | inherited | — |
| Package expired/consumed | 410 | reuse `BundleExpiredException` semantics | — |

### 3.6 Configuration surface

**Server `application.yml`:**

```yaml
app:
  renewal:
    storage-path: ${RENEWAL_STORAGE_PATH:/opt/certguard/renewals}   # req #4 public-cert store
    package-download-ttl-seconds: ${RENEWAL_PACKAGE_TTL_SECONDS:86400}
    ca:
      provider: ${RENEWAL_CA_PROVIDER:NONE}   # NONE => NoopCaProvider (stub); future: LETS_ENCRYPT etc.
  agent:
    job:
      max-attempts: ${AGENT_JOB_MAX_ATTEMPTS:5}
      stale-claim-minutes: ${AGENT_JOB_STALE_CLAIM_MINUTES:10}
```

**Agent `application.properties` (new keys):**

| Key | Default | Purpose |
|---|---|---|
| `certguard.install.deploy-hook` | (empty) | Tier 2: fixed path to a customer-written deploy script. Empty = Tier 1 only. The agent executes this exact path; it never builds a shell command from user input. Customer writes bash/PowerShell/any executable. |
| `certguard.install.deploy-hook-timeout-seconds` | `30` | Tier 2 timeout. Expiry or non-zero exit → `HOOK_FAILED` (req #8). |
| `certguard.install.private-key-dir` | next to cert | Where `CsrGenerator` writes the locally-generated private key (mode 0600). Never transmitted. |

**Deploy hook env vars** (metadata only — no secrets on the command line): `CERT_PATH`, `CERT_DOMAIN`, `RENEWAL_ID`.

**Docker:** mount `RENEWAL_STORAGE_PATH` as a persistent shared volume (multi-replica; ShedLock is wired per GAPS N6). See Q3.

---

## 4. Impact Analysis

| Component | File | Impact | Detail |
|---|---|---|---|
| `AgentController` | `AgentController.java` | Medium (additive) | Add `GET /delivery-jobs`, `POST /jobs/{jobId}/csr`, `GET /jobs/{jobId}/package`, `POST /jobs/{jobId}/report`. Scan endpoints untouched (B2). New mappings must be relative (prefix-concat warning at `:39-42`). |
| `AgentService` | `AgentService.java` | Low | Scan `pollJobs` unchanged. New logic in `AgentJobService`. |
| `CertificateRenewalController` | NEW | — | Hosts the 5 user-facing renewal endpoints. |
| `CertificateRenewalService` | NEW | — | Orchestrator. |
| `AgentJobService` | NEW | — | Durable queue + stale-claim sweep. |
| `CertificatePackageStore` | NEW | — | Server-side public-cert storage. |
| `CaProvider` / `NoopCaProvider` | NEW | — | Pluggable CA stub. |
| `CsrGenerator` | NEW (agent) | — | BouncyCastle keypair + PKCS#10 CSR. |
| `CertInstaller` | NEW (agent) | — | Two-tier install (Q2). |
| Agent `PollLoop` | `PollLoop.java` | Medium | New delivery/CSR phase after scan block (`:97`), isolated from scan errors. |
| Agent `ServerApiClient` | `ServerApiClient.java` | Medium | 4 new methods; first binary-download path; reuses `addAgentHeaders`. |
| `NotificationService` | `NotificationService.java` | Medium | Signature of `dispatchExpiryAlert` widened to carry `CertificateRecord`; 3 new `@Async` methods. |
| `CertificateExpiryScheduler` | `CertificateExpiryScheduler.java` | Low–Medium | Pass `CertificateRecord` into widened `dispatchExpiryAlert` (data already in hand). |
| Email templates | `templates/email/` | Medium | `expiry-warning(.txt)` + `expiry-critical(.txt)`: add `th:if/${agentDiscovered}` deep-link button and `th:unless` static note. Add 3 new templates: `renewal-ready`, `renewal-installed`, `renewal-failed` (HTML + `.txt`). |
| `CertificateService` / repo | `CertificateService.java` | Low | Add `findByIdAndOrgId` if not present. `@Transactional` already at `:23`. |
| `GlobalExceptionHandler` | existing | Low | Add 4 new exception mappings. |
| `docker-compose.yml` | `docker-compose.yml` | Low | Add shared renewal-storage volume + env vars. |
| Frontend SPA | `ui/` | Medium | `/certificates/{certId}` route must survive login→`returnTo` redirect (Q6). "Request Renewal" action button (agent-discovered targets only). Renewal status panel polling `GET /renewals/{id}` with state machine: `REQUESTED → CSR_PENDING → CSR_RECEIVED → CA_PENDING → ... → DELIVERED` (+ `FAILED`/`CANCELLED`). Render `failureReason`/agent `errorDetail` on FAILED state. |
| RabbitMQ | — | None | Not used. Does not resolve GAPS R9. |

**Required (not optional):** `AgentJobService.resetStaleClaimedJobs` — the existing `AgentService.resetStaleClaimedJobs` (`AgentService.java:303-318`) only sweeps `agent_scan_jobs`. Without a parallel sweep for `agent_jobs`, a delivery/CSR job claimed by an agent that then crashes hangs in `CLAIMED` forever.

---

## 5. Risks (for GAPS.md)

| Risk | Mitigation |
|---|---|
| Shared package storage in multi-replica (Q3) | Require shared persistent volume (`RENEWAL_STORAGE_PATH`); document S3 / object storage as the recommended follow-up. |
| At-least-once duplicate installs (Q7) | Checksum-gated idempotent install: overwrite when checksum differs, no-op when identical. Atomic write prevents partial files. |
| Binary download is a new pattern for the agent client | `SecureHttpClient` TLS pin + hostname verification still apply; add a byte-array body handler. |
| Unbounded retry | `attempt_count` + `max-attempts` → terminal FAILED + user notification. |
| ~~Sensitive-data-at-rest (private keys)~~ | **Eliminated by Q8.** Agent generates CSR locally; server stores public material only. No at-rest encryption required. |

---

## 6. Open Questions — All Resolved

| Q | Resolution |
|---|---|
| **Q1 — CA package shape** | Public material only (Q8): leaf + chain PEM. `CaIssuedPackage` carries `leafPem` + `chainPem` + combined `bytes`; `content_type = application/x-pem-file`. Agent already holds the matching private key locally. |
| **Q2 — Install mechanism** | Two-tier: Tier 1 Java `java.nio` atomic local write + POSIX/ACL permissions (always runs); Tier 2 optional fixed-path deploy hook (certbot/acme.sh pattern), metadata via env vars only, configurable timeout. Remote copy, server reload, ACL changes handled by the customer's hook script. |
| **Q3 — Multi-replica storage** | Require shared persistent volume for `app.renewal.storage-path`; document S3/object storage as recommended follow-up. Packages are public material (Q8), so no encryption-at-rest required. |
| **Q4 — Agent endpoint** | Separate `GET /api/v1/agent/delivery-jobs` (B2). Scan endpoint untouched. Fully backward-compatible. |
| **Q5 — Who may request renewal** | ADMIN/ENGINEER only (VIEWER excluded). PLATFORM_ADMIN acting-as renewals audited via `PlatformAdminAuditService`. |
| **Q6 — Deep-link auth** | Deep link targets `app.ui-base-url + /certificates/{certId}`; relies on SPA login→`returnTo` flow. Frontend must ensure `returnTo` param survives the gateway/auth-service redirect. |
| **Q7 — Idempotency** | Overwrite-in-place when checksum differs; no-op when identical. Atomic write prevents partial files. |
| **Q8 — Private key handling** | **Agent generates keypair + CSR locally. Private key never transits the server.** Server forwards CSR to CA; stores and relays only the signed public cert + chain. `certificate_renewal_requests.csr_pem` holds the CSR for the CA call. Sensitive-data-at-rest risk is fully eliminated. |
