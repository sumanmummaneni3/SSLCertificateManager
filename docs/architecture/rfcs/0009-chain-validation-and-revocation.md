# RFC 0009 — Certificate Chain Validation and Revocation Checking: `RevocationCheckService`, `ChainValidationService`, `REVOKED` status, full-chain wire contract, and daily `RevocationRecheckScheduler`

- **Status:** Accepted (2026-06-20) — all §10 questions ratified by product owner; ready for implementation
- **Authors:** CertGuard Architect
- **Relates to:** RFC 0008 (`ExpiryEvaluationService` convergence point, post-scan hook, ShedLock sweep pattern), HLD §3 (scan flows), LLD §5 (status derivation) / §6 (error model), GAPS N15 (new — see §11)
- **Supersedes:** the expiry-only status model (`ExpiryEvaluationService.determineCertStatus` as the sole status authority)

## 0. Grounding

Anchored against live source under `server/src/main/java/com/certguard/` and `agent/src/main/java/com/certguard/agent/`:

- **Neither scanner validates the chain or checks revocation.** Both install a trust-all `X509TrustManager`:
  - Agent: `scanner/SslScanner.java:196-202` (`buildTrustAll`), used by `scanCtxBcJsse()` (`:166-180`) and `scanCtxJvm()` (`:182-194`).
  - Server: inline trust-all at `service/SslScannerService.java:107-111`.
- **Both read leaf fields only.** Agent `full(...)` at `scanner/SslScanner.java:283-301` uses `chain[0]` (`:54`); `chainDepth` is just `chain.length` (`:297`). Server `persistCertificates` at `service/SslScannerService.java:129-167` uses `chain[0]` (`:132`) and **discards `chain[1..n]`** (does not even set `chainDepth`).
- **The full chain reaches the socket but is thrown away.** `ssl.getSession().getPeerCertificates()` returns the server-sent chain at `scanner/SslScanner.java:250` and `service/SslScannerService.java:124`; only element 0 is retained.
- **The agent wire contract carries no chain or revocation fields.** `agent/model/ScanResult.java:1-68` ships serial, notAfter, notBefore, CN, issuer, key alg/size, sig alg, `chainDepth` (int), SANs, and a single `publicCertB64` (the leaf). No intermediates, no revocation, no chain-trust result. Server-side request DTO `AgentScanResultRequest` mirrors this (consumed in `service/AgentService.java:235-244`).
- **`CertStatus` cannot express revocation.** `enums/CertStatus.java:2` = `{ VALID, EXPIRING, EXPIRED, UNREACHABLE, UNKNOWN }`. It is a Postgres `cert_status` ENUM (entity mapping `entity/CertificateRecord.java:42-46`), so adding a value requires a Flyway migration, not just a Java change.
- **A single status authority exists and is expiry-only.** `service/ExpiryEvaluationService.java:155-161` (`determineCertStatus`) is pure date math: `< 0 → EXPIRED`, `<= warningDays → EXPIRING`, else `VALID`. It is mathematically incapable of producing `REVOKED`.
- **All three write-paths funnel status through it:** server at `service/SslScannerService.java:150`; agent FULL at `service/AgentService.java:245`; agent DELTA at `service/AgentService.java:264`.
- **The notify pipeline already has an AFTER_COMMIT convergence point** (RFC 0008): `service/ExpiryEvaluationService.java:225-235` registers a `TransactionSynchronization` that calls `notificationService.dispatchExpiryAlert(...)` after commit. We extend this rather than introduce a parallel path.
- **`CertificateRecord` has the stored leaf available for offline re-checks:** `publicCertB64` at `entity/CertificateRecord.java:39-40`. There are **no** revocation columns.
- **The schedulers package + ShedLock pattern is established:** `CertificateExpiryScheduler.java:58-97` (`@Scheduled` + `@SchedulerLock`), mirrored by `SslScannerService.scheduledPublicScan` (`:44-52`) and the RFC 0008 `PrivateScanScheduler`. New periodic work follows this shape.

### 0.1 Motivating incident

CertGuard's own cloud certificate, `cloud.oopsssl.co.uk`, was **revoked by Let's Encrypt for `keyCompromise`**. Browsers reject it with `NET::ERR_CERT_REVOKED`. CertGuard continued to report the target as `VALID` because the only status input is days-to-expiry and the cert had not yet expired. This RFC closes that blind spot: a revoked-but-not-expired certificate is exactly the case the current pipeline cannot represent or detect.

## 1. Summary

Add two server-side capabilities to the scan→evaluate→persist→notify pipeline:

1. **Chain validation** — build the full chain to a configured trust anchor and record whether it is trusted, with a structured failure reason.
2. **Revocation checking** — determine revocation status via **OCSP stapling → OCSP request → CRL fallback**, record the result and reason (including `keyCompromise`), and surface `REVOKED` as a first-class `CertStatus`.

Revocation runs **server-side** (CA endpoints are public-internet hosts the on-prem agent often cannot/should-not reach). The agent is extended only to ship the **full chain** (`chainB64`) so the server can build and validate it offline. A new daily, ShedLock-guarded `RevocationRecheckScheduler` re-checks non-expired certs from their stored leaf, decoupled from scan cadence (a cert can be revoked between scans). Status derivation gains a single precedence rule: **`REVOKED > EXPIRED > INVALID > EXPIRING > VALID`** (with `UNREACHABLE`/`UNKNOWN` orthogonal — see §3.5). `INVALID` is a new status set when a **public** target presents an untrusted/invalid chain (§10.4, decided).

## 2. Goals / Non-Goals

### Goals (in scope)

- **G1 — Full chain construction + trust-anchor validation.** Build `leaf → intermediate(s) → root` and validate against a trust store using `CertPathValidator` (PKIX). Record `chain_trusted` + structured `chain_validation_error`.
- **G2 — Revocation via the standard cascade:** OCSP **stapling** inspection first (zero extra network, no privacy leak), then an **OCSP request** to the AIA responder, then **CRL** fallback via CDP. Record `revocation_status`, `revocation_source`, `revocation_reason`, `revocation_checked_at`.
- **G3 — `REVOKED` as a first-class status** that dominates expiry-derived status.
- **G4 — Single convergence point** for status derivation (extend, do not fork, the RFC 0008 service) and for notification dispatch.
- **G5 — Periodic re-check** independent of scans, operating on the stored leaf.
- **G6 — Configurable soft-fail/hard-fail policy** (default soft-fail) at global + per-org granularity.
- **G7 — Agent stays thin:** the only agent change is sending the chain; no OCSP/CRL logic, no new heavy deps (honors the agent guardrail).

### Non-Goals (explicitly out of scope)

- **NG1 — Certificate Transparency (CT) log checks / SCT validation.** Future RFC.
- **NG2 — Deep cipher/protocol auditing / TLS grading** (the agent's CVE-audited cipher list at `scanner/SslScanner.java:116-149` is scan-compatibility tooling, not an audit product). Future RFC.
- **NG3 — CAA record checking, key-usage policy linting, or weak-key detection** beyond what's already captured (`keyAlgorithm`/`keySize`).
- **NG4 — Acting on revocation** (auto-renewal, auto-disable target). We detect, record, and alert only. Renewal is `certguard-renewal-service` territory (GAPS R9 decision).
- **NG5 — Agent-side revocation checking.** Revocation runs server-side (§3.3). The agent never contacts OCSP/CRL endpoints.
- **NG6 — Real-time revocation push** (e.g., OCSP must-staple enforcement at handshake time, CRLite, CRLSets). Periodic re-check is the mechanism.

## 3. Design

### 3.1 Components

- **`ChainValidationService`** (new `@Service`, `com.certguard.service`) — given the full chain (leaf + intermediates) and a trust store, builds and validates the path; returns a `ChainValidationResult`.
- **`RevocationCheckService`** (new `@Service`, `com.certguard.service`) — given the validated chain (or at minimum leaf + issuer), runs the OCSP-stapling → OCSP → CRL cascade; returns a `RevocationResult`. Uses JDK `PKIXRevocationChecker` for OCSP+CRL with BouncyCastle for OCSP response parsing/signature verification where the JDK API is insufficient. **No new heavyweight dependency** beyond BouncyCastle, already present server-side.
- **`RevocationRecheckScheduler`** (new, schedulers package) — daily `@Scheduled` + `@SchedulerLock`, paged over non-expired certs, decodes `publicCertB64`, re-runs revocation, converges status.
- **`CertStatusService`** (rename/extension of the status logic in `ExpiryEvaluationService`) — the **single** function that combines revocation + chain-trust + expiry into a final `CertStatus` (§3.5). To minimize churn, this can stay as a method in `ExpiryEvaluationService`; the rename is cosmetic.

### 3.2 Integration into the existing pipeline

The cascade runs **after the cert row is built and before status is set**, at the three existing convergence calls. The status call gains two inputs.

**Server / public path** — `service/SslScannerService.java`:
- The chain is fetched at `:124` and currently reduced to `chain[0]` at `:132`. **Stop discarding `chain[1..n]`**: pass the full `X509Certificate[]` to `ChainValidationService` and `RevocationCheckService`, and set `chainDepth` (currently unset on this path).
- Replace the status call at `:150` with the combined derivation (§3.5) using the revocation + chain results.
- The existing `expiryEvaluationService.evaluateAndNotify(record, mode, previousLastScannedAt)` at `:155` continues to drive notification; revocation alerting rides the same AFTER_COMMIT hook (§3.6).

**Agent path** — `service/AgentService.java`:
- `processFull` (`:224-252`): build the chain from the new `chainB64` (§3.4) + existing `publicCertB64`, run chain validation + revocation, replace the status call at `:245`.
- `processDelta` (`:254-270`): DELTA does **not** re-send the cert (`agent/model/ScanResult.java:29-30`). Revocation on a DELTA uses the **already-stored leaf** (`publicCertB64`) for that record — i.e., the server re-checks revocation itself rather than trusting the absence of change. Replace the status call at `:264`. (This is the key reason revocation must be server-side: serial-keyed DELTA can't observe a revocation that happened with no serial change.)

### 3.3 Why revocation runs server-side

- **Network egress.** OCSP responder (AIA `1.3.6.1.5.5.7.1.1`) and CRL distribution points (CDP `2.5.29.31`) are public-internet URLs. The on-prem agent scans **private** endpoints precisely because they're unreachable from the cloud; requiring the agent to reach public CA infrastructure inverts that and imposes egress requirements on customer networks (HLD §1 boundary).
- **Thin agent.** No OCSP/CRL/BouncyCastle-path logic on the agent (guardrail: no heavy deps, small JAR).
- **Decoupling from scan cadence.** Revocation is time-varying independently of the cert bytes. Re-checking from the stored leaf (`publicCertB64`) lets the server re-verify daily without an agent round-trip and without defeating the serial-keyed DELTA optimization (`scanner/SslScanner.java:58-75`).
- **Centralized CA traffic.** One egress point, one cache, one place to enforce OCSP privacy mitigations (§9).

### 3.4 Agent wire-contract change (chain delivery)

Add an ordered list of base64 DER certs (leaf first, then intermediates; root optional/ignored) to both the agent model and the server request DTO:

- `agent/model/ScanResult.java`: add `private List<String> chainB64;` populated in `full(...)` (`scanner/SslScanner.java:283-301`) from the already-available `chain` array (`Base64` each `cert.getEncoded()`), leaf at index 0. **DELTA leaves `chainB64` null** (unchanged contract — `:303-312`).
- `AgentScanResultRequest` (server): add the matching `List<String> chainB64` field; `processFull` decodes it.
- **Backward compatibility:** `chainB64` is **optional**. Older agents that don't send it → server falls back to AIA `caIssuers` fetch to complete the chain, or records `chain_validation_error = INCOMPLETE_CHAIN_NO_INTERMEDIATES` and treats chain-trust as `UNKNOWN` (soft-fail, §6). No agent forced upgrade. Revocation can still proceed if the issuer cert is obtainable (stapled OCSP needs no issuer; OCSP request and CRL need the issuer cert).

### 3.5 Status derivation — single convergence point and precedence

A revoked/untrusted certificate is more urgent than an expiring one and orthogonal to reachability. New combined derivation (extends `ExpiryEvaluationService.determineCertStatus`, currently `:155-161`):

```
determineCertStatus(expiry, revocationResult, chainResult, target, orgId):
    if revocationResult.status == REVOKED      -> REVOKED        // dominates everything
    if (days < 0)                              -> EXPIRED        // expired dominates INVALID
    if (!chainResult.trusted && !target.isPrivate) -> INVALID    // public target, untrusted chain (§10.4)
    if (days <= warningDays)                    -> EXPIRING
    return VALID
```

**Precedence: `REVOKED > EXPIRED > INVALID > EXPIRING > VALID`.**

- `REVOKED` is set **only** on a definitive `REVOKED` revocation result (hard signal). `UNKNOWN`/responder-error revocation results never set `REVOKED` (soft-fail, §6) — they are recorded in `revocation_status` but do not change `CertStatus`.
- **`INVALID` (decided §10.4):** when a **public** target presents an untrusted or otherwise invalid chain (`chain_trusted=false`), `CertStatus` becomes `INVALID` **and** a chain advisory alert is raised. This matches browser behavior (`NET::ERR_CERT_AUTHORITY_INVALID`) for public-facing sites. `EXPIRED` and `REVOKED` still dominate `INVALID` (an expired or revoked cert is the more actionable headline).
- **Private targets stay advisory-only:** an untrusted/self-signed chain is the norm on private targets (the agent's entire reason for existing), so private-target chain failures do **not** change `CertStatus` — they surface via `chain_trusted=false` + `chain_validation_error` and a lower-severity advisory alert gated by `app.chain.alert-on-untrusted` (per-org overridable, default `false`).
- In all cases the structured `chain_validation_error` is recorded regardless of whether `CertStatus` changes.
- `UNREACHABLE` (handshake failed entirely, `service/SslScannerService.java:169-178`) short-circuits before any chain/revocation work — there's no cert to check.

### 3.6 Notification integration

- **Revocation alert** is a new, **CRITICAL-and-immediate** event. It reuses the AFTER_COMMIT dispatch (`service/ExpiryEvaluationService.java:225-235`) but **bypasses the expiry dedup window** (RFC 0008 §2.3 dedup at `:187-194`) — a `REVOKED` transition fires once on the VALID/EXPIRING→REVOKED edge. To avoid re-alert storms on every daily re-check, add a `last_revocation_alert_sent_at` stamp (mirrors `last_alert_sent_at` at `entity/CertificateRecord.java:83-84`) and only alert on **state transition into REVOKED** (or if `last_revocation_alert_sent_at` is null).
- **Severity/reason** carried in the `ExpiryAlertContext` analog (extend `dto/internal/ExpiryAlertContext` or add `RevocationAlertContext`) — pre-resolved in-transaction to avoid the `LazyInitializationException` class of bug documented at `service/ExpiryEvaluationService.java:217-222`.

### 3.7 `RevocationRecheckScheduler`

- Daily `@Scheduled(cron = "${app.revocation.recheck.schedule-cron:0 0 4 * * *}")`, `@SchedulerLock(name="RevocationRecheckScheduler_recheck", lockAtMostFor="PT1H", lockAtLeastFor="PT10M")` — mirrors `CertificateExpiryScheduler.java:58-97` and the RFC 0008 `PrivateScanScheduler`.
- Eligible set: `certificate_records` where `status != EXPIRED` AND `status != UNREACHABLE` AND (`revocation_checked_at IS NULL` OR `< now - app.revocation.recheck.min-age-hours`). Paged in chunks of `app.revocation.recheck.batch-size` (default 500), `org_id`-aware for fairness.
- For each: decode `publicCertB64`, complete chain from stored chain bytes / AIA, run `RevocationCheckService`, converge status (§3.5), and on a transition into/out-of REVOKED fire/clear the revocation alert (§3.6) via the AFTER_COMMIT hook.
- This job, not the scan path, is what makes between-scan revocations detectable (the incident requirement).

## 4. Data model

### 4.1 `CertStatus` enum — add `REVOKED` and `INVALID`

- Java: `enums/CertStatus.java:2` → `{ VALID, EXPIRING, EXPIRED, UNREACHABLE, UNKNOWN, REVOKED, INVALID }`.
- Postgres: `cert_status` is a NAMED ENUM (`entity/CertificateRecord.java:42-46`). **`ALTER TYPE ... ADD VALUE` cannot run inside a transaction** on PostgreSQL (and not at all mid-transaction on older versions). Therefore this migration **must be its own standalone Flyway script** with transactional execution disabled.

`Vn__add_cert_status_revoked_invalid.sql`:
```sql
-- Flyway: this migration must NOT run in a transaction (ALTER TYPE ADD VALUE restriction).
-- Configure executeInTransaction=false for this script (Flyway script config or
-- -- comment directive supported by the configured Flyway version).
ALTER TYPE cert_status ADD VALUE IF NOT EXISTS 'REVOKED';
ALTER TYPE cert_status ADD VALUE IF NOT EXISTS 'INVALID';
```
> Implementation note for backend-engineer: set `executeInTransaction=false` via the per-migration `.conf` (`Vn__add_cert_status_revoked.sql.conf`) since `FlywayConfig.java` uses `validateOnMigrate(true)` (GAPS R4). Keep this enum-add in a **separate file** from the column-add below.

### 4.2 New columns on `certificate_records`

`Vn+1__add_revocation_and_chain_columns.sql` (runs transactionally):
```sql
ALTER TABLE certificate_records
  ADD COLUMN revocation_status        text,          -- GOOD | REVOKED | UNKNOWN | UNCHECKED
  ADD COLUMN revocation_source        text,          -- OCSP_STAPLED | OCSP | CRL | NONE
  ADD COLUMN revocation_reason        text,          -- mapped reason (e.g. KEY_COMPROMISE); null unless REVOKED
  ADD COLUMN revocation_reason_code   smallint,      -- raw RFC 5280 CRLReason (0..10); null unless REVOKED
  ADD COLUMN revoked_at               timestamptz,   -- revocationDate from OCSP/CRL; null unless REVOKED
  ADD COLUMN revocation_checked_at    timestamptz,   -- last time a revocation check completed (any result)
  ADD COLUMN last_revocation_alert_sent_at timestamptz,
  ADD COLUMN chain_trusted            boolean,       -- null = not yet evaluated / UNKNOWN
  ADD COLUMN chain_validation_error   text,          -- structured enum string; null when trusted
  ADD COLUMN revocation_deep_check    boolean NOT NULL DEFAULT false;  -- §10.2: per-cert "complete check" (query OCSP AND CRL)
```
- `revocation_deep_check` (decided §10.2) is a per-certificate opt-in surfaced as a UI toggle. When `true`, `RevocationCheckService` runs **both** OCSP and CRL and reconciles (REVOKED sticky) instead of stopping at the first definitive result. Default `false` (stop-at-first). A future bulk/group selection to flip this across many certs is noted but out of scope here.
- Entity additions mirror these on `entity/CertificateRecord.java` (after `:84`). `revocation_status`/`chain_validation_error` modeled as String-backed enums in Java (avoid more Postgres ENUMs to keep migrations simple; these aren't queried as enums).
- **No new table is required.** All fields are per-cert and 1:1 with `certificate_records`. (If an audit history of revocation transitions is later wanted, that *would* be a child table and **must denormalize `org_id`** alongside the FK per the house convention at `entity/CertificateRecord.java:21-22` — but that is deferred, see §10.6.)

### 4.3 Per-org policy (reuse `notification_settings`)

Add columns to the RFC 0008 `notification_settings` table rather than a new table:
```sql
ALTER TABLE notification_settings
  ADD COLUMN revocation_check_enabled boolean NOT NULL DEFAULT true,
  ADD COLUMN revocation_fail_mode     text    NOT NULL DEFAULT 'SOFT',  -- SOFT | HARD
  ADD COLUMN alert_on_untrusted_chain boolean NOT NULL DEFAULT false;
```
Resolution chain reuses the existing per-target → org-default → app.yml fallback in `ExpiryEvaluationService.resolveSettings` (`:246-264`).

### 4.4 Deferred — `certificate_revocation_events` history table (design ready, NOT built in v1)

Decided §10.6: v1 ships with the mutable columns in §4.2 only. To make the audit/history table a clean drop-in later, the **`RevocationCheckService` MUST publish a domain event at the single convergence point on every status transition** (GOOD↔REVOKED↔hold↔UNKNOWN), even in v1 where the only subscriber is logging/metrics. When the table is added, an `@TransactionalEventListener(phase = AFTER_COMMIT)` persists each event — no change to the check logic.

Future migration (append-only child table; **denormalizes `org_id`** alongside the cert FK per house convention, mirroring `platform_admin_audit`/`org_audit`):
```sql
CREATE TABLE certificate_revocation_events (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    certificate_id      uuid        NOT NULL REFERENCES certificate_records(id) ON DELETE CASCADE,
    org_id              uuid        NOT NULL,                 -- denormalized for org-scoped queries
    previous_status     text,                                -- prior revocation_status (null on first observation)
    new_status          text        NOT NULL,                -- GOOD | REVOKED | UNKNOWN | UNCHECKED
    source              text        NOT NULL,                -- OCSP_STAPLED | OCSP | CRL | NONE
    reason              text,                                -- mapped reason (e.g. KEY_COMPROMISE); null unless REVOKED
    reason_code         smallint,                            -- raw RFC 5280 CRLReason
    revoked_at          timestamptz,                         -- from OCSP/CRL when REVOKED
    observed_at         timestamptz NOT NULL DEFAULT now(),  -- when CertGuard detected the transition
    deep_check          boolean     NOT NULL DEFAULT false   -- whether this was a query-both check (§10.2)
);
CREATE INDEX idx_cre_cert_observed ON certificate_revocation_events (certificate_id, observed_at DESC);
CREATE INDEX idx_cre_org_observed  ON certificate_revocation_events (org_id, observed_at DESC);
```
Rows are written **only on transition**, not on every recheck, so volume stays low. Purpose: compliance evidence ("when did we first detect / first alert"), flapping-responder forensics, and `certificateHold`→cleared→re-revoked timelines.

## 5. EXHAUSTIVE error-case matrix

Legend:
- **Detection point:** S=`ChainValidationService`, R=`RevocationCheckService`, H=handshake (scanner). Codes in `chain_validation_error` / `revocation_status` are the exact persisted strings.
- **`CertStatus`** is the *final* status after §3.5 precedence (expiry may still upgrade VALID→EXPIRING/EXPIRED unless REVOKED dominates; "expiry-derived" = whatever date math yields).
- **Alert?** "Revocation alert" = CRITICAL, transition-gated (§3.6). "Chain advisory" = low-severity, only if `alert_on_untrusted_chain=true`. "Expiry alert" = existing RFC 0008 behavior, unaffected.
- **Default policy below is SOFT-FAIL** (§6). Hard-fail deviations are called out in Notes.

### 5.1 Chain-validation cases

> **Governing rule (decided §10.4):** for every `chain_trusted=false` row below, the **Resulting CertStatus** column reads "expiry-derived" — this holds for **private** targets only. For **public** targets, any `chain_trusted=false` outcome sets `CertStatus=INVALID` (unless `EXPIRED`/`REVOKED` dominate per §3.5) **and always raises a chain advisory** (not gated by `alert-on-untrusted`). Private targets stay advisory-only, gated by `app.chain.alert-on-untrusted`. The `chain_validation_error` is recorded either way.

| Case | Detection | Resulting CertStatus | chain fields | Alert? | Notes |
|---|---|---|---|---|---|
| Chain builds to a configured trust anchor, valid | S | expiry-derived | `chain_trusted=true`, error=null | none (chain) | Happy path. |
| Untrusted root / unknown CA (anchor not in trust store) | S | expiry-derived (unchanged) | `chain_trusted=false`, `UNTRUSTED_ANCHOR` | chain advisory if enabled | Very common on private targets; do NOT fail CertStatus by default (§3.5). |
| Incomplete chain — server sent leaf only, no intermediates, none fetchable via AIA | S | expiry-derived | `chain_trusted=false`, `INCOMPLETE_CHAIN` | chain advisory if enabled | Also the legacy-agent (`chainB64` absent) fallback case. |
| Incomplete chain but AIA `caIssuers` fetch completes it, then trusts | S | expiry-derived | `chain_trusted=true`, error=null | none | Best-effort completion; record `revocation_source` unaffected. |
| Self-signed leaf (subject==issuer, no separate CA) | S | expiry-derived | `chain_trusted=false`, `SELF_SIGNED` | chain advisory if enabled | Common on appliances/IPMI. Revocation skipped (`UNCHECKED`/`NONE` — no issuer to query). |
| Expired intermediate or root within the path | S | expiry-derived (leaf may still be in-date) | `chain_trusted=false`, `EXPIRED_CHAIN_ELEMENT` | chain advisory if enabled | Distinct from leaf expiry; leaf-expiry still drives EXPIRED via date math if applicable. |
| Path-length constraint violated (basicConstraints `pathLenConstraint`) | S | expiry-derived | `chain_trusted=false`, `PATH_LEN_VIOLATION` | chain advisory if enabled | PKIX rejects path. |
| Name-constraints violation (issuer's `nameConstraints` excludes leaf SAN) | S | expiry-derived | `chain_trusted=false`, `NAME_CONSTRAINT_VIOLATION` | chain advisory if enabled | PKIX rejects path. |
| Basic-constraints: leaf not a CA but used as issuer / CA bit missing on intermediate | S | expiry-derived | `chain_trusted=false`, `BASIC_CONSTRAINT_VIOLATION` | chain advisory if enabled | PKIX rejects. |
| Signature in chain doesn't verify (tampered/mismatched issuer) | S | expiry-derived | `chain_trusted=false`, `SIGNATURE_INVALID` | chain advisory if enabled | Strong tamper signal; consider escalation on public targets (§10.4). |
| Weak signature algorithm disabled by JDK policy (e.g. MD5/SHA-1 root) | S | expiry-derived | `chain_trusted=false`, `WEAK_ALGORITHM` | chain advisory if enabled | Driven by `jdk.certpath.disabledAlgorithms`. Record, don't fail CertStatus. |
| Chain validation throws (unexpected `CertPathValidatorException` subtype) | S | expiry-derived | `chain_trusted=false`, `CHAIN_ERROR:<reason>` | chain advisory if enabled | Catch-all; log at WARN with reason. |
| **Any `chain_trusted=false` on a PUBLIC target** | S | **INVALID** (unless EXPIRED/REVOKED dominate) | `chain_trusted=false`, `<specific error>` | **chain advisory (always)** | **Decided §10.4** — public targets fail to INVALID + advisory. `INVALID` never overrides a definitive REVOKED or an EXPIRED. Still never fabricates REVOKED from a chain failure. |

### 5.2 Revocation cases (cascade: stapling → OCSP → CRL)

| Case | Detection | Resulting CertStatus | revocation fields | Alert? | Notes |
|---|---|---|---|---|---|
| OCSP **stapled** response present, signature valid, status GOOD, fresh | R | expiry-derived | `GOOD`, src=`OCSP_STAPLED`, checked_at=now | none | Preferred: no extra network, no privacy leak. |
| OCSP stapled response present, status **REVOKED** | R | **REVOKED** | `REVOKED`, src=`OCSP_STAPLED`, reason+code+revoked_at set | **revocation alert** (transition-gated) | The `cloud.oopsssl.co.uk` class of incident. |
| No stapled response → OCSP request to AIA responder, GOOD | R | expiry-derived | `GOOD`, src=`OCSP`, checked_at=now | none | Apply privacy mitigations (§9). |
| OCSP request → **REVOKED** | R | **REVOKED** | `REVOKED`, src=`OCSP`, reason/code/revoked_at | **revocation alert** | reason mapping §5.3. |
| OCSP request → **unknown** (responder doesn't know the serial) | R | expiry-derived (unchanged) | `UNKNOWN`, src=`OCSP` | none (soft) / advisory (hard) | "unknown" ≠ revoked; never set REVOKED. May indicate wrong responder or pre-issuance. |
| OCSP responder **unreachable / connection refused** | R | expiry-derived | `UNKNOWN`, src=`OCSP`, checked_at=now | none (soft) / advisory (hard) | Fall through to CRL before concluding UNKNOWN. |
| OCSP responder **timeout** (> `app.revocation.ocsp.timeout-ms`) | R | expiry-derived | `UNKNOWN`, src=`OCSP` | none (soft) / advisory (hard) | Fall through to CRL. Record timeout in metrics. |
| OCSP **signature invalid** / responder cert not authorized (not issuer, no OCSP-signing EKU, not delegated) | R | expiry-derived | `UNKNOWN`, src=`OCSP`, error noted | none (soft) / advisory (hard) | **Never trust an unverifiable OCSP response** (§9). Treat as UNKNOWN, fall to CRL. |
| OCSP response **stale** (`nextUpdate` in the past) | R | expiry-derived | `UNKNOWN`, src=`OCSP` | none (soft) | Reject stale per RFC 6960; fall to CRL. |
| OCSP `thisUpdate` in the future / clock skew beyond tolerance | R | expiry-derived | `UNKNOWN`, src=`OCSP` | none (soft) | Allow small skew (`app.revocation.clock-skew-sec`, default 300). |
| OCSP `tryLater` / `internalError` / `malformedRequest` / `sigRequired` / `unauthorized` (response status ≠ successful) | R | expiry-derived | `UNKNOWN`, src=`OCSP` | none (soft) | Fall to CRL. |
| OCSP nonce mismatch (if nonce sent) | R | expiry-derived | `UNKNOWN`, src=`OCSP` | none (soft) | Possible replay; do not trust. Fall to CRL. |
| No AIA OCSP URL in cert → go straight to CRL | R | per CRL outcome | src=`CRL` (or `NONE`) | per CRL row | Skip OCSP entirely. |
| CRL fetched, parsed, valid, **leaf not listed** | R | expiry-derived | `GOOD`, src=`CRL`, checked_at=now | none | Definitive GOOD via CRL. |
| CRL fetched, valid, **leaf listed as revoked** | R | **REVOKED** | `REVOKED`, src=`CRL`, reason/code/revoked_at from CRL entry | **revocation alert** | CRL entry reason code mapped (§5.3). |
| CRL **unreachable / timeout** | R | expiry-derived | `UNKNOWN`, src=`CRL` | none (soft) / advisory (hard) | Both OCSP and CRL failed → UNKNOWN. |
| CRL **parse failure** (malformed DER/PEM) | R | expiry-derived | `UNKNOWN`, src=`CRL` | none (soft) / advisory (hard) | Log, count metric. |
| CRL **signature invalid** (not signed by issuer / wrong key) | R | expiry-derived | `UNKNOWN`, src=`CRL` | none (soft) / advisory (hard) | Never trust unverifiable CRL (§9). |
| CRL **stale** (`nextUpdate` passed) | R | expiry-derived | `UNKNOWN`, src=`CRL` | none (soft) | Configurable grace `app.revocation.crl.stale-grace-hours` (default 0). |
| CRL too large (> `app.revocation.crl.max-bytes`) | R | expiry-derived | `UNKNOWN`, src=`CRL` | none (soft) | DoS guard (§9); skip download. |
| **No CDP and no AIA** in cert (no revocation info at all) | R | expiry-derived | `UNCHECKED`, src=`NONE` | none | Cannot check; record UNCHECKED, never REVOKED. Common on private/self-signed. |
| Self-signed leaf (no issuer) | R (skipped) | expiry-derived | `UNCHECKED`, src=`NONE` | none | Nothing to query; pairs with `SELF_SIGNED` chain row. |
| **Both OCSP and CRL return GOOD** (defense in depth, if configured to check both) | R | expiry-derived | `GOOD`, src=first-definitive | none | Default: stop at first definitive GOOD/REVOKED (don't double-query). |
| OCSP says GOOD but CRL says REVOKED (or vice-versa) — only if both queried | R | **REVOKED** | `REVOKED`, src=whichever says revoked | **revocation alert** | REVOKED is sticky: any definitive revoked wins. Log the discrepancy. |
| Network egress blocked at server (firewall/proxy) for ALL CA endpoints | R | expiry-derived | `UNKNOWN`, src=last-attempted | none (soft) | Distinguish from per-responder failure via metric; alert ops, not customer. |

### 5.3 Revocation reason mapping (RFC 5280 §5.3.1 CRLReason → `revocation_reason`)

| Code | RFC 5280 name | `revocation_reason` (stored) | Alert emphasis |
|---|---|---|---|
| 0 | unspecified | `UNSPECIFIED` | normal |
| 1 | keyCompromise | `KEY_COMPROMISE` | **HIGHEST** — the motivating case; copy prominently into alert |
| 2 | cACompromise | `CA_COMPROMISE` | **HIGHEST** |
| 3 | affiliationChanged | `AFFILIATION_CHANGED` | normal |
| 4 | superseded | `SUPERSEDED` | normal (often benign rotation) |
| 5 | cessationOfOperation | `CESSATION_OF_OPERATION` | normal |
| 6 | certificateHold | `CERTIFICATE_HOLD` | **special** — temporary; status MAY revert. Re-check can clear it → treat as REVOKED while held, allow transition back to GOOD. |
| 8 | removeFromCRL | `REMOVE_FROM_CRL` | clears a prior hold → not revoked |
| 9 | privilegeWithdrawn | `PRIVILEGE_WITHDRAWN` | normal |
| 10 | aACompromise | `AA_COMPROMISE` | high |
| (absent) | reason not provided | `UNSPECIFIED` | normal |

> `certificateHold` (6) is the one reversible state: a held cert can be un-revoked. The re-check job (§3.7) must allow `REVOKED(hold) → GOOD` transitions and clear `CertStatus` accordingly. All other reasons are terminal.

### 5.4 Status-interaction edge cases

| Case | Resulting CertStatus | Notes |
|---|---|---|
| **Revoked but not expired** (the incident) | **REVOKED** | REVOKED dominates VALID/EXPIRING (§3.5). Primary win condition. |
| **Expired AND revoked** | **REVOKED** | REVOKED > EXPIRED. We still record `revoked_at` and `expiry_date`; alert mentions both. (Open Q §10.3 — some prefer EXPIRED here; recommend REVOKED for security clarity.) |
| Revoked, then cert rotated to a new (good) serial on next scan | new record `VALID` | New serial → new `certificate_records` row (`findByTargetIdAndSerialNumber`, `service/SslScannerService.java:141-144`). Old revoked row retained for history. Target's *current* status reflects the new cert. |
| Handshake fails entirely (TLS error / connection refused) | **UNREACHABLE** | Short-circuits before chain/revocation (`service/SslScannerService.java:169-178`). No revocation fields touched (stay as last-known). |
| Public target, cert in-date, chain untrusted, revocation UNKNOWN | **INVALID** | §10.4: public + untrusted chain → INVALID + advisory. Revocation UNKNOWN stays soft (doesn't add REVOKED). |
| Private target, cert in-date, chain untrusted, revocation UNKNOWN | expiry-derived (VALID/EXPIRING) | Private targets stay advisory-only; both signals recorded, status unaffected. |
| First-ever scan, revocation UNKNOWN (responder down) under SOFT | expiry-derived | Never invent REVOKED from absence of data. `revocation_status=UNKNOWN`. |
| Re-check finds previously-GOOD cert now REVOKED (no rescan, stored leaf) | **REVOKED** | `RevocationRecheckScheduler` win; this is how the incident would have been caught between scans. |

## 6. Soft-fail vs hard-fail

**Default: SOFT-FAIL.** Rationale:

- A responder being unreachable, slow, or returning `unknown` is **not** evidence of revocation. Marking such certs `REVOKED` (hard-fail) would generate false `NET::ERR_CERT_REVOKED`-equivalent alarms on healthy certs every time a CA's OCSP responder hiccups — eroding trust in the alerts and burying the real `keyCompromise` signal.
- Browsers themselves predominantly soft-fail OCSP for exactly this reason.
- The **only** state that sets `CertStatus=REVOKED` is a **definitive REVOKED** from a **signature-verified** OCSP/CRL response. Soft-fail governs the *non-definitive* outcomes (UNKNOWN/unreachable/stale/bad-signature → `revocation_status=UNKNOWN`, `CertStatus` unchanged).

**Hard-fail is configurable** (`revocation_fail_mode = HARD`) globally (`app.revocation.fail-mode`) and per-org (`notification_settings.revocation_fail_mode`, §4.3). In HARD mode:
- A non-definitive revocation outcome does **not** become `REVOKED` either (we never fabricate revocation). Instead it **raises an advisory alert** ("revocation could not be confirmed for N days") and may be surfaced distinctly in the UI. HARD-fail elevates **alert severity / visibility**, it does **not** change the `REVOKED` determination logic.
- Recommended for compliance-sensitive orgs that want to be told when assurance is degraded.

## 7. Migration & rollout

1. **Migration A** (standalone, non-transactional): add `REVOKED` to `cert_status` (§4.1).
2. **Migration B**: add revocation/chain columns to `certificate_records` and policy columns to `notification_settings` (§4.2–4.3). All new columns nullable / defaulted → safe online add.
3. **Phase 1 (shadow mode):** deploy `ChainValidationService` + `RevocationCheckService` + scheduler with `app.revocation.enabled=true` but `app.revocation.shadow=true` — compute and **record** `revocation_status`/`chain_trusted` but **do not** set `CertStatus=REVOKED` and **do not** alert. Validate against real traffic (and against the known `cloud.oopsssl.co.uk` case) without risk.
4. **Phase 2 (enforce):** flip `app.revocation.shadow=false`. REVOKED status + revocation alerts go live. Default soft-fail.
5. **Agent rollout:** ship `chainB64` in the agent in any release; server already tolerates its absence (§3.4). No coordinated deploy required.

### 7.1 Config keys (`application.yml`, all overridable by env per house convention)
```yaml
app:
  revocation:
    enabled: true
    shadow: false
    fail-mode: SOFT                 # SOFT | HARD (global default; org can override)
    clock-skew-sec: 300
    ocsp:
      enabled: true
      timeout-ms: 4000
      use-nonce: false             # privacy/perf tradeoff, §9
    crl:
      enabled: true
      timeout-ms: 8000
      max-bytes: 5242880           # 5 MiB DoS guard
      stale-grace-hours: 0
      cache-ttl-minutes: 60
    recheck:
      schedule-cron: "0 0 4 * * *"
      min-age-hours: 20
      batch-size: 500
  chain:
    enabled: true
    trust-store: SYSTEM            # SYSTEM (JDK cacerts) | path to custom truststore
    alert-on-untrusted: false      # advisory alerts for chain failures (per-org overridable)
    complete-via-aia: true         # fetch missing intermediates via AIA caIssuers
```

### 7.2 Observability / metrics (Micrometer)
- `certguard.revocation.check.total{source,result}` — counts by `OCSP_STAPLED|OCSP|CRL` × `GOOD|REVOKED|UNKNOWN|UNCHECKED`.
- `certguard.revocation.check.duration` — timer per source.
- `certguard.revocation.responder.failure.total{source,reason}` — unreachable/timeout/bad-signature/stale.
- `certguard.chain.validation.total{result}` — trusted vs each `chain_validation_error`.
- `certguard.revocation.revoked.total{reason}` — **alert ops on `KEY_COMPROMISE`/`CA_COMPROMISE` spikes.**
- Structured WARN log on every REVOKED determination and every bad-signature responder result.

### 7.3 Test plan hooks (for test-engineer)
- Unit: reason-code mapping (§5.3), status precedence (§3.5), soft-fail vs hard-fail branching, stale/skew rejection.
- Integration: a fixture cert with a known-revoked serial (the `cloud.oopsssl.co.uk` / Let's Encrypt R12 case) → asserts `CertStatus=REVOKED`, `revocation_reason=KEY_COMPROMISE`, one transition-gated alert.
- Responder fault injection: OCSP timeout, bad-signature, `tryLater`, stale; CRL unreachable/malformed/oversize → all assert `UNKNOWN` + `CertStatus` unchanged under SOFT.
- Backward-compat: agent result without `chainB64` → `INCOMPLETE_CHAIN` recorded, no failure.
- Scheduler: between-scan revocation detected by `RevocationRecheckScheduler` on stored leaf; ShedLock single-runner under two instances.

## 8. Reserved

(Folded into §7.)

## 9. Security considerations

- **Never trust an unverified revocation response.** Every OCSP response must be signature-verified against the issuer or an authorized delegated responder (OCSP-signing EKU `1.3.6.1.5.5.7.3.9`, or signed by the issuer key). Every CRL must be signature-verified against the issuer. Unverifiable → `UNKNOWN`, never GOOD and never REVOKED (matrix §5.2). This prevents a MITM from forging a "GOOD" to mask a real revocation, or forging a "REVOKED" to DoS a healthy target's status.
- **OCSP privacy leakage.** An OCSP request tells the CA which cert (hence which site) we're checking and from which egress IP. Mitigations: prefer **stapling** (no request at all); centralize egress (single server IP, not per-customer); make nonce optional (`use-nonce: false` default — nonces defeat responder caching and add round-trips); cache GOOD responses to `nextUpdate`. Document that the server contacts public CA endpoints (privacy note for customers).
- **DoS / resource guards.** Cap CRL download size (`crl.max-bytes`), enforce timeouts on every fetch, cap redirects, and use a bounded HTTP connection pool dedicated to CA endpoints so revocation traffic can't starve the scan pool.
- **SSRF.** AIA/CDP URLs come from the (untrusted) scanned cert. Only follow `http`/`https` schemes, block requests to private/loopback/link-local ranges (the responder should be public CA infra), and never let a cert's AIA point the server at internal services.
- **Trust store integrity.** If `trust-store` is a custom path, it must be a read-only mounted secret; document that adding a CA there widens what we'll call "trusted."
- **Replay.** Reject stale OCSP (`nextUpdate` passed) and enforce `thisUpdate`/skew bounds (matrix §5.2).
- **Hard-fail abuse.** Because hard-fail never fabricates REVOKED, a flood of responder errors cannot be weaponized to mark a competitor's healthy cert as revoked.

## 10. Decisions (all ratified by product owner, 2026-06-20)

1. **§10.1 Trust anchor source. — DECIDED (2026-06-20): JDK `cacerts` now, Mozilla bundle later.** Initial implementation uses the JDK `cacerts` trust store (`app.chain.trust-store: SYSTEM`, §7.1) for zero-config, zero-maintenance shipping. Long-term we will switch to a curated bundle tracking the **Mozilla CA list** to match real browser behavior (the product's promise is "will users see a warning?"). The `app.chain.trust-store` key already accepts a bundle path, so the shift is config + a bundle-refresh process, not a code change. Tracking item: add a Mozilla-bundle refresh job/process before the switch.
2. **§10.2 Both-check policy. — DECIDED (2026-06-20): stop-at-first default + per-cert "complete check" override.** Default is stop-at-first (cheapest, browser-grade). Additionally expose a **per-certificate UI toggle** ("complete revocation check" / deep check) that forces query-both (OCSP **and** CRL) for that cert, persisted as `certificate_records.revocation_deep_check` (§4.2). Future enhancement: a way to **group/bulk-select** certs that should run complete checks (e.g. a saved filter or tag-driven batch) — not built now. REVOKED stays sticky: in deep mode, either source saying revoked wins.
3. **§10.3 Expired-and-revoked precedence. — DECIDED (2026-06-20): REVOKED.** Expired-and-revoked resolves to `REVOKED` (security-forward). `revoked_at` and `expiry_date` are both recorded; the alert mentions both.
4. **§10.4 Chain failure on PUBLIC targets. — DECIDED (2026-06-20): FAIL + ADVISORY.** An untrusted/invalid chain on a *public* target sets `CertStatus=INVALID` (new status, §4.1) **and** raises a chain advisory. Private targets stay advisory-only. Precedence `REVOKED > EXPIRED > INVALID > EXPIRING > VALID`.
5. **§10.5 `certificateHold` distinction. — DECIDED (2026-06-20): distinguish it.** A `CERTIFICATE_HOLD` revocation is surfaced in the UI/alerts as a **reversible "Suspended (on hold)"** state, visually/severity-distinct from terminal revocation, driven by the stored `revocation_reason`. Mechanics unchanged (status REVOKED while held; daily recheck may transition back to GOOD if cleared — §5.3). Still a real alert, just tagged reversible.
6. **§10.6 Revocation history table. — DECIDED (2026-06-20): defer, design ready (see §4.4).** v1 ships with mutable columns only. `RevocationCheckService` MUST emit a transition event at the single convergence point so the append-only `certificate_revocation_events` table (designed in §4.4) is a drop-in later. Likely wanted for compliance customers.
7. **§10.7 Per-org egress. — DECIDED (2026-06-20): ship the flag.** Per-org `notification_settings.revocation_check_enabled` (§4.3) ships, default **on** (`true`). An org may set it `false` to forbid the cloud from contacting third-party CA endpoints about their certs (privacy/sovereignty) — accepting that they then get no revocation detection. (A future `STAPLING_ONLY` tri-state is possible but not built now.)

## 11. GAPS.md entry to add (Section 3 — New Gaps)

> ### N15 — No certificate chain validation or revocation checking; `REVOKED` status unrepresentable (HIGH)
>
> Both scanners install a trust-all `X509TrustManager` (`agent/.../scanner/SslScanner.java:196-202`; `server/.../service/SslScannerService.java:107-111`) and read **leaf fields only** (`SslScanner.java:283-301`; `SslScannerService.java:129-167`). The full chain reaches the socket (`getPeerCertificates()` at `SslScanner.java:250`, `SslScannerService.java:124`) but `chain[1..n]` is discarded. There is **no** OCSP-stapling inspection, OCSP request, CRL fetch/parse, AIA/CDP handling, or `PKIXRevocationChecker` anywhere in the tree. `CertStatus` (`enums/CertStatus.java:2`) has no `REVOKED` value, and the sole status authority `ExpiryEvaluationService.determineCertStatus` (`:155-161`) is pure date math — it can never produce `REVOKED`. Agent wire contract (`agent/model/ScanResult.java`) carries no chain or revocation fields.
>
> **Impact (confirmed in production):** CertGuard's own cloud cert `cloud.oopsssl.co.uk` was revoked by Let's Encrypt for `keyCompromise`; browsers show `NET::ERR_CERT_REVOKED`; CertGuard reported it `VALID` because it had not yet expired.
>
> **Action:** Implement RFC 0009 — `ChainValidationService` + `RevocationCheckService` (server-side; OCSP-stapling → OCSP → CRL, soft-fail default), `RevocationRecheckScheduler` (daily, ShedLock), add `REVOKED` and `INVALID` to the `cert_status` enum (standalone non-transactional Flyway migration) + revocation/chain columns on `certificate_records`, and a `chainB64` field on the agent scan result so the server can build the full chain offline. Status precedence `REVOKED > EXPIRED > INVALID > EXPIRING > VALID`. Decided 2026-06-20: expired-and-revoked → REVOKED (§10.3); public-target untrusted chain → INVALID + advisory, private advisory-only (§10.4).
