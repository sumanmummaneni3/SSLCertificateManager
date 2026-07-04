# RFC 0013 — Agent as the Universal Scan Plane

**Status**: Accepted — architect-reviewed, all findings applied and re-verified (2026-07-02)
**Author**: Claude (team-lead), on request from Suman
**Relates to**: RFC 0005 (no-broker decision), RFC 0008 (expiry notification), RFC 0009 (chain validation & revocation), RFC 0011 (network discovery)

---

## TL;DR

Today public targets are scanned **in-process** by `SslScannerService` (a fixed 20-thread pool inside the web app), while private targets go through the battle-tested agent job queue (`agent_scan_jobs`, `FOR UPDATE SKIP LOCKED`, HMAC-signed results). In a cloud deployment the in-process path is the weak link: egress restrictions, WAF/geo-blocking of unknown scanner IPs, and scan work competing with request traffic on the web tier.

We unify: **all scans flow through the existing job queue.** Platform-operated "cloud scanner" agents — the same agent JAR, run by us — claim public-target jobs from a shared pool. The server stops opening sockets to customer targets entirely. Customers get fixed, publishable scanner egress IPs; scanning scales independently of the web tier; retries and stale-job recovery come free from infrastructure that already exists. No message broker is needed — Postgres `SKIP LOCKED` remains the queue, consistent with RFC 0005's no-broker decision (GAPS R9 is already closed as a no-op; RabbitMQ was never in the live tree).

---

## Problem

1. **Cloud egress is unreliable for scanning.** The SaaS server may sit behind NAT/egress policies; targets behind WAFs/CDNs reject probes from unrecognized IPs; geo-blocked targets are unreachable from the server's single region.
2. **Scanning inside the web app doesn't scale.** `SslScannerService.scheduledPublicScan` builds a 20-platform-thread pool and blocks up to 30 minutes per sweep (`SslScannerService.java:81-92`). Horizontal scaling of the web tier is gated by ShedLock, so adding replicas adds zero scan throughput.
3. **Two code paths, one job.** Direct scans and agent scans persist certificates through different code (`SslScannerService.persistCertificates` vs `AgentService.processFull/processDelta`), which have already drifted (e.g. OCSP-staple capture exists only on the direct path).
4. **Transactional bug on the direct path.** `scheduledPublicScan → scanTargets → scanSingleTarget` is same-class self-invocation, so `@Transactional` on `scanSingleTarget` is never applied on the scheduled path (`SslScannerService.java:94-97`).

## What already exists (and is reused unchanged)

- `agent_scan_jobs` queue with atomic claim via `FOR UPDATE SKIP LOCKED` (`AgentScanJobRepository.java:29-38`).
- Stale-claim recovery: CLAIMED > 10 min → PENDING every 5 min (`AgentService.resetStaleClaimedJobs`, `AgentService.java:445-460`).
- HMAC-signed result submission + BCrypt agent-key auth (`AgentService.submitResult`, `AgentAuthFilter`).
- FULL/DELTA scan protocol with `lastKnownSerialHash` delta optimization (`AgentService.pollJobs`).
- Full-chain upload (`AgentScanResultRequest.chainB64`, RFC 0009 §3.4) — server-side `ChainValidationService` + `RevocationCheckService` can run on agent results, so validation parity with the direct path is achievable server-side. Trust decisions stay on the server; agents remain dumb probes.
- ShedLock (verified wired: `SchedulerLockConfig` + `V18__shedlock.sql`) for the enqueue schedulers.

---

## Decision

**Adopt Option A: a shared job pool claimed by platform scanner agents.** The server becomes an *enqueuer + result processor*; it never dials customer targets. A `HYBRID` migration mode keeps the in-process scanner as fallback until the pool is proven.

### Options considered

| Option | Description | Decision |
|--------|-------------|----------|
| **A — shared pool (chosen)** | Public jobs enqueued with `agent_id = NULL`, `job_kind = PUBLIC_POOL`; any healthy platform scanner claims them via `SKIP LOCKED`. | **Adopted** — natural load balancing, no coupling of enqueue to scanner availability, scanner death handled by existing stale-claim reset. |
| B — assign scanner at enqueue | Round-robin a scanner agent onto each job at insert time (keeps `agent_id NOT NULL`). | Rejected — enqueue must know scanner health; a dead scanner strands its jobs for 10+ min; re-implements load balancing the queue gives free. |
| C — message-broker dispatch | Fan out scan jobs via a broker (RabbitMQ). | Rejected — no broker exists in the live tree (RFC 0005 decided no-broker; GAPS R9 closed as no-op). Postgres `SKIP LOCKED` already provides these semantics at our scale (<100k targets). |
| D — external scan SaaS / serverless probes | Lambda/Cloud Functions probes per region. | Rejected for now — new runtime, new secrets surface, loses agent code reuse. Revisit if scanner fleet ops become a burden. |

---

## Design

### 1. Data model (migration `V41__scanner_pool.sql`)

```sql
-- Agent type: customer-deployed vs platform-operated scanner
CREATE TYPE agent_type AS ENUM ('CUSTOMER', 'PLATFORM_SCANNER');
ALTER TABLE agents ADD COLUMN agent_type agent_type NOT NULL DEFAULT 'CUSTOMER';

-- Pool jobs have no pre-assigned agent
ALTER TABLE agent_scan_jobs ALTER COLUMN agent_id DROP NOT NULL;
ALTER TABLE agent_scan_jobs ADD COLUMN job_kind VARCHAR(20) NOT NULL DEFAULT 'AGENT_PINNED';
  -- 'AGENT_PINNED' (today's behavior) | 'PUBLIC_POOL'
ALTER TABLE agent_scan_jobs ADD COLUMN attempts INT NOT NULL DEFAULT 0;
-- error text goes in the EXISTING error_msg VARCHAR(500) column (AgentScanJob.java:36-37,
-- surfaced via ScanStatusResponse.errorMsg) — no new column.

CREATE INDEX idx_scan_jobs_pool_pending
  ON agent_scan_jobs (created_at)
  WHERE job_kind = 'PUBLIC_POOL' AND status = 'PENDING';
```

Notes:
- `scan_job_status` already contains `FAILED` (`V3__agent_schema.sql:6`) — no enum change needed; the Java `ScanJobStatus` enum matches. Verified V40 is the latest migration, so V41 is free.
- **JPA mapping must change with the DDL**: `AgentScanJob.agent` is currently `@ManyToOne @JoinColumn(nullable = false)` (`AgentScanJob.java:16-18`). Flip to `nullable = true` and allow `agent = null` in the builder, or `ddl-auto: validate` and pool-row inserts will fail.

Constraint (enforced in service layer + CHECK): `job_kind = 'AGENT_PINNED' ⇒ agent_id IS NOT NULL`; `job_kind = 'PUBLIC_POOL' ⇒ agent_id IS NULL` until claimed (claim stamps `agent_id` for audit + result verification).

Platform scanner agents belong to a reserved **platform organization** (same pattern as platform-admin), not to any customer org. `agent_scan_jobs.org_id` continues to carry the *target's* org for tenancy of results.

### 2. Enqueue path (server)

- `SslScannerService.scheduledPublicScan` is replaced by `PublicScanEnqueueScheduler`: same cron, ShedLock-guarded, iterates `findAllByIsPrivateFalseAndEnabledTrue()` and inserts `PUBLIC_POOL` jobs (dedup: skip if PENDING/CLAIMED job exists for target — same guard as `queueScanJob`, `AgentService.java:400-406`).
- `TargetService.triggerScan` for public targets enqueues a `PUBLIC_POOL` job with `trigger_source = USER` (FORCE evaluation mode flows through the existing RFC 0008 §6.3 mapping in `submitResult`).
- `queueScanJob`'s `target.getAgent() == null` guard (`AgentService.java:397-398`) branches on target privacy: private targets still require an assigned agent; public targets take the pool path.
- **Deliberate behavior change (decision, not accident):** scheduled public scans today bypass `SubscriptionGuard` entirely (`SslScannerService.scheduledPublicScan` never calls it). The pool enqueue path calls `subscriptionGuard.assertScansAllowed(orgId)` (`AgentService.java:393`), so suspended orgs' public targets will **stop being scanned**. This is intended — it aligns public scans with the existing private-scan enforcement.

### 3. Claim path

New repository method mirroring `claimPendingJobsWithLock`:

```sql
SELECT * FROM agent_scan_jobs
WHERE job_kind = 'PUBLIC_POOL' AND status = 'PENDING'
ORDER BY created_at
LIMIT :batch
FOR UPDATE SKIP LOCKED
```

`AgentService.pollJobs` branches on `agent.getAgentType()`:
- `CUSTOMER` → today's behavior, unchanged.
- `PLATFORM_SCANNER` → claim from the pool query; stamp `agent_id`, `claimed_at`, `status = CLAIMED`. **Do not** merge `networkScanService.pollNetworkJobs(agent)` on this branch (`AgentService.java:196`) — NETWORK_SCAN jobs are customer-agent-only.

Batch size for scanners is config-driven (`app.scanning.pool.claim-batch`, default 25), not `maxTargets`.

**DELTA behavior in the pool.** The agent's DELTA optimization is a per-agent in-memory cache keyed by targetId (`SslScanner.java:42,94-108`). Pool jobs bounce across scanner replicas, so cache hits will be rare — pool scans will be mostly FULL. That is correct (just heavier bandwidth) and acceptable for v1. Scanner agents must add **eviction** (size- or TTL-bounded) to that cache: unlike a customer agent capped at `maxTargets`, a pool scanner sees the global public-target population and the cache would otherwise grow unbounded.

### 4. Result path & security invariants

`AgentService.submitResult` looks up the job by `findByIdAndAgentId` and the target *independently* by `request.targetId` (`AgentService.java:212-221`) — nothing binds the two together except (on the pinned path) `target.agent == agent`. The checks must **branch by job kind**, and the job↔target binding must become explicit:

| Check | AGENT_PINNED (today + hardened) | PUBLIC_POOL (new) |
|---|---|---|
| Job↔target binding | **NEW (defense-in-depth):** assert `request.targetId == job.target.id` | **REQUIRED:** assert `request.targetId == job.target.id` — without this, a compromised scanner could submit a cert result for *any* public target of *any* org (cross-tenant result injection), since `target.agent` is null for pool targets and no other check binds the result to the claimed job |
| Ownership | `target.agent == agent` (`AgentService.java:219-221`), unchanged | `job.agent == agent` (the claimer) **and** `job.job_kind == PUBLIC_POOL` **and** `agent.agent_type == PLATFORM_SCANNER` |
| Address policy | `validateCidr(host, allowedCidrs)` — must be inside customer's private scope | **Inverse**: no resolved address may be non-public (SSRF guard, see below) |

**SSRF guard (hard requirement).** A malicious/compromised tenant could add a "public" target of `169.254.169.254` or `10.0.0.5` hoping a scanner inside our VPC scans it.

*Do NOT reuse `NicSubnetDiscovery.isPrivateCidr` or `Rfc1918Util.isRfc1918` for this check.* Both cover only RFC1918 (`Rfc1918Util.java:41-43`, `NicSubnetDiscovery.java:98-114`) and **fail open** on exactly the addresses that matter: `169.254.169.254` (link-local / cloud metadata), `127.0.0.0/8`, `0.0.0.0`, CGNAT `100.64.0.0/10`, and all IPv6 (`::1`, ULA `fc00::/7`, link-local `fe80::/10`, IPv4-mapped `::ffff:a.b.c.d`).

Specify a purpose-built `PublicAddressGuard` (one implementation, shared verbatim between server and agent modules):
1. Resolve the hostname to **all** A/AAAA records; reject if **any** resolved address is disallowed (a single-record check is DNS-rebinding-bait).
2. Unwrap IPv4-mapped IPv6 addresses before classification.
3. Reject when `InetAddress.isLoopbackAddress() || isLinkLocalAddress() || isSiteLocalAddress() || isAnyLocalAddress() || isMulticastAddress()`, plus explicit ranges the JDK predicates miss: `100.64.0.0/10` (CGNAT), `169.254.0.0/16` (belt-and-braces alongside `isLinkLocalAddress`), IPv6 ULA `fc00::/7`.
4. Enforce at **both** ends, and re-resolve at scan time:
   - Server-side at target creation/enqueue: `HostTypeDetector.shouldDefaultToPrivate` already flips obvious private hosts; add a hard reject via `PublicAddressGuard` for `PUBLIC_POOL` jobs.
   - Agent-side at scan time (authoritative — DNS can change between enqueue and scan): scanner agents re-resolve and refuse to connect to any disallowed address. This check applies **only** when `agent_type = PLATFORM_SCANNER` — customer agents scan private space by design.

(`NicSubnetDiscovery.java:56` already uses the right `InetAddress` primitives for NIC filtering — that pattern, not `isPrivateCidr`, is the model.)

**Validation parity.** `PUBLIC_POOL` FULL results must carry `chainB64` (already in the DTO); `processFull` runs `ChainValidationService` + `RevocationCheckService` server-side exactly as `SslScannerService.persistCertificates` does today (`SslScannerService.java:172-209`), including shadow-mode handling. This closes the existing drift where agent-scanned certs skip revocation.

**OCSP staple**: extend `ScanResult`/`AgentScanResultRequest` with optional `ocspStapleB64` so the pool path keeps the staple capture the direct path has (`SslScannerService.java:139-147`).

### 5. Failure handling & UNREACHABLE hysteresis

**The ERROR-result path is new work, not a small addition — it is in scope.** Today the agent silently drops failures: `PollLoop.processCertScanJob` logs and returns on ERROR without submitting anything (`PollLoop.java:148-152`), `AgentScanResultRequest` has no error field, and `submitResult` throws on any unknown `scanType` (`AgentService.java:239-240`). `ScanResult.Type.ERROR` exists in the agent model but is never transmitted. Without this work, a failing pool job loops PENDING → CLAIMED → stale-reset → PENDING forever and never reaches FAILED. Required pieces:
- Agent: `PollLoop` submits ERROR results (HMAC-signed like FULL/DELTA) instead of dropping them.
- DTO: add `errorMessage` to `AgentScanResultRequest`.
- Server: `submitResult` gains an `ERROR` branch.

Flow:
- Scanner-side failure → agent reports result type `ERROR` with the exception message; server increments `attempts`, sets the existing `error_msg` column, and re-queues (status back to PENDING) until `attempts >= 3`, then marks the job FAILED.
- Only after **two consecutive FAILED jobs** (not one failed cycle) does the server mark the target's certs `UNREACHABLE` — fixes today's flapping where 3 in-process retries over ~6 seconds flip statuses (`SslScannerService.java:230-239`).
- Pool starvation alarm: a scheduler (ShedLock) alerts platform admins if any `PUBLIC_POOL` job is PENDING > 15 min (means no scanner is polling) — reuses the `AgentOfflineScheduler` notification shape.

### 6. Scanner fleet provisioning & ops

- Same agent JAR, `agent.mode=AUTHENTICATED`, enrolled into the platform org via the existing encrypted-bundle provisioning flow (GAPS N3). New config key `agent.scanner-pool=true` is advisory only — the server-side `agent_type` (set at token-mint time by platform admin) is authoritative.
- Deployed as containers alongside the server (compose service `scanner-agent`, N replicas) and later as regional VMs with **static egress IPs published in docs** so customers can allowlist them.
- Scanner heartbeats flow through the existing heartbeat/offline-alert machinery.

### 7. Migration & rollout (`app.scanning.mode`)

| Mode | Behavior |
|---|---|
| `DIRECT` (default at merge) | Today's behavior. Pool code dark. |
| `HYBRID` | Enqueue to pool; if a job sits PENDING > 10 min, `PublicScanFallbackScheduler` executes it in-process via the retained direct scanner and marks it COMPLETED with `result_source = DIRECT_FALLBACK`. |
| `POOL` (end state) | In-process scanner disabled; pool only. |

Ship dark → enable `HYBRID` on VPS with 2 scanner replicas → observe one full sweep cycle + forced scans → flip to `POOL` → delete `SslScannerService.fetchCertificateChain` and the thread pool in a follow-up.

While in `DIRECT`/`HYBRID`, fix the `@Transactional` self-invocation on the retained direct path by extracting persistence into the shared result-processing bean (see §8).

### 8. Code consolidation

Extract a `CertificatePersistenceService` used by both `AgentService.processFull/processDelta` and the retained direct/fallback path — single place for: upsert `CertificateRecord`, chain validation, revocation, shadow-mode status, `evaluateAndNotify`, `lastScannedAt` stamping. This kills the drift class of bugs permanently and fixes the self-invocation transaction bug as a side effect.

### 9. Frontend impact

- **Target detail / certificate cards**: show scan provenance — "Scanned via CertGuard Cloud Scanner" vs "Scanned via agent *{name}*" (new `scanSource` field on certificate/target responses).

**Ratified API contract (architect-reviewed, FE already built against it — authoritative):**

```jsonc
// scanSource — on certificate / target / scan-status responses
{ "type": "CLOUD_SCANNER" | "CUSTOMER_AGENT", "agentId": uuid|null, "agentName": string|null }
```
- `agentId`/`agentName` are **always null for CLOUD_SCANNER** — the platform scanner's identity must not leak into a customer tenant's response (tenant-boundary rule).
- Provenance is only emitted when explicitly recorded post-V41: pool completions → `CLOUD_SCANNER`; customer-agent completions → `CUSTOMER_AGENT` + name; `DIRECT_FALLBACK` executions explicitly stamp `CLOUD_SCANNER`. **Legacy records (pre-V41, no recorded scanner) omit the field entirely** — a bare "null agent ⇒ CLOUD_SCANNER" default would mislabel old private-target certs that were actually scanned by customer agents. The UI hides the label when absent.

```jsonc
// pendingScanQueuedAt — on target responses (nullable Instant, not a boolean)
"pendingScanQueuedAt": "2026-07-02T16:00:00Z" | null   // oldest PENDING job's created_at
```
Populated via batch-load in `listTargets` (no N+1); the 10-minute "delayed" threshold stays client-side.

```jsonc
// GET /api/v1/admin/scanner-pool — platform-admin only, platform-global
{
  "scanners": [ { "id": uuid, "name": string, "status": string, "lastSeenAt": instant,
                  "jobsClaimedLastHour": number, "totalJobsCompleted": number } ],
  "backlog":  { "pendingCount": number, "oldestPendingAgeMinutes": number,
                "claimedCount": number, "failedLast24h": number }
}
```
`oldestPendingAgeMinutes` (minutes, not seconds — the shipped UI consumes this name); backlog counts `PUBLIC_POOL` jobs only.
- **Add-target flow**: public targets no longer show/require agent assignment (already implied, verify no regression); private targets unchanged.
- **Agents page**: platform scanners must NOT appear in customer org agent lists (they live in the platform org, so org-scoping already hides them — verify). Platform-admin console gets a **Scanner Pool** panel: scanner list, last-seen, jobs claimed/hour, pool backlog depth (PENDING count + oldest age).
- **Scan status UX**: user-triggered public scans become async (job round-trip up to one poll interval, default ≤ 60 s) instead of near-instant — today `TargetService.triggerScan` runs the public scan synchronously (`TargetService.java:206-209`). The existing private-target pattern applies: "Scan queued" toast + poll/refresh of target status. Verify the UI doesn't assume synchronous completion for public targets (e.g. immediate cert refresh after the POST). Positive side-effect: `getLatestScanStatus` currently returns null for public targets because they never have job rows (`TargetService.java:226-242`); once public targets get pool jobs, the existing scan-status polling endpoint works for them **unchanged**.
- **Backlog/health surfacing**: if a scan job is PENDING > 10 min, target row shows a "scan delayed" hint (tooltip: scanner pool busy/offline).

### 10. Explicitly out of scope

- Regional scanner routing / geo-affinity (future: `region` column on agents + jobs).
- Cloud-provider connectors (ACM/Key Vault), CT-log monitoring, push ingestion API — separate RFCs.
- Anonymous-scan flow (RFC 0011 Part B) — untouched.

---

## Test plan (summary)

- Unit: pool claim query (concurrent claim → no double-claim), submitResult branch matrix (pinned vs pool × customer vs scanner agent), SSRF guard (RFC1918/loopback/link-local/metadata rejected for pool jobs only).
- Integration: enqueue → claim → FULL result → chain+revocation fields persisted identical to direct-path output for the same endpoint; ERROR result → attempts++ → FAILED after 3 → UNREACHABLE after 2 consecutive FAILED.
- Unit: `PublicAddressGuard` — table-driven over `169.254.169.254`, `127.0.0.1`, `0.0.0.0`, `10.x`/`172.16.x`/`192.168.x`, `100.64.0.1`, `::1`, `fe80::1`, `fc00::1`, `::ffff:10.0.0.1`, multi-record DNS where one record is private.
- Migration: V41 applies on a copy of prod schema; existing AGENT_PINNED rows unaffected (`job_kind` default).
- E2E in `HYBRID`: kill all scanners → fallback executes within 10 min → statuses correct.

## Open questions

1. Should the platform org for scanners be a fixed well-known UUID (seeded in V41) or created lazily at first scanner-token mint? (Lean: seeded — simpler auth checks.)
2. Poll interval for platform scanners: default agent interval or tighter (e.g. 15 s) for snappier forced scans?
3. Do we cap per-org pool throughput to stop one tenant's 10k targets starving others (fair-share `ORDER BY` on org job counts), or is FIFO fine for v1? (Lean: FIFO v1, note as follow-up.)
