# RFC 0009 — Implementation Plan (Work Packages)

**Source of truth:** `docs/architecture/rfcs/0009-chain-validation-and-revocation.md` (Status: Accepted; all §10 decisions ratified).

**Migration numbers (confirmed 2026-06-20):** latest applied is `V33__create_notification_settings.sql`. Use **V34** (enum) and **V35** (columns).

**Ratified decisions reflected throughout:**
- Two new statuses: **`REVOKED`** and **`INVALID`**.
- Precedence: **`REVOKED > EXPIRED > INVALID > EXPIRING > VALID`** (`UNREACHABLE`/`UNKNOWN` orthogonal).
- Revocation cascade: **OCSP-stapling → OCSP → CRL**, **stop-at-first-definitive** by default; **per-cert `revocation_deep_check`** toggle forces query-both.
- `certificateHold` (code 6) is **reversible** → surfaced as **"Suspended (on hold)"**; allows `REVOKED(hold) → GOOD`.
- History table **deferred**, but **emit transition events now** (logging + metrics subscribers).
- `revocation_check_enabled` per-org, **default ON**.
- Trust store = **JDK cacerts now**, Mozilla bundle later (config-switchable via `app.chain.trust-store`).
- **Soft-fail default** (never fabricate `REVOKED`/`INVALID` from responder errors); HARD-fail elevates alert visibility only.

## Sequencing (both teams)
1. **BE-1 → BE-2 → BE-3** (migrations + enum/entity) land first; nothing compiles against new columns until then.
2. **Cross-team API contract (below)** is frozen before **FE** integration begins.
3. **BE-4/BE-5** (services) → **BE-7** (convergence) → **BE-8** (scheduler) → **BE-9/10/11** → **BE-12** (endpoints).
4. **BE-6** (agent wire) is independent and backward-compatible; can land any time.
5. **BE** ships in **shadow mode** (`app.revocation.shadow=true`) first; FE can build against shadow data.
6. **FE** depends on BE-3 (serialized fields) and BE-12 (deep-check + settings endpoints).

---

## CROSS-TEAM API CONTRACT (freeze before FE integration)

Both engineers must honor these shapes. Backend owns implementation; frontend consumes.

### Certificate object — new fields (added to existing cert response DTO)
```jsonc
{
  // ... existing fields (commonName, issuer, serialNumber, expiryDate, status, ...)
  "status": "REVOKED",                       // now also: REVOKED | INVALID  (+ existing VALID|EXPIRING|EXPIRED|UNREACHABLE|UNKNOWN)
  "revocationStatus": "REVOKED",             // GOOD | REVOKED | UNKNOWN | UNCHECKED | null
  "revocationSource": "OCSP_STAPLED",        // OCSP_STAPLED | OCSP | CRL | NONE | null
  "revocationReason": "KEY_COMPROMISE",      // mapped enum string (see RFC §5.3) | null
  "revocationReasonCode": 1,                 // RFC 5280 CRLReason 0..10 | null
  "revokedAt": "2026-06-10T14:22:00Z",       // ISO-8601 | null
  "revocationCheckedAt": "2026-06-20T04:00:00Z",
  "revocationDeepCheck": false,              // per-cert query-both toggle
  "chainTrusted": true,                      // true | false | null (not yet evaluated)
  "chainValidationError": null,              // structured enum string (RFC §5.1) | null
  "onHold": false                            // server-derived: true iff revocationStatus==REVOKED && revocationReasonCode==6
}
```
> `onHold` is a server-derived convenience boolean. Backend MUST emit it so FE doesn't reimplement the rule.

### Endpoint — per-cert deep-check toggle (BE-12; FE-3 depends on it)
```
PATCH /api/v1/organizations/{orgId}/certificates/{certId}/revocation-deep-check
Request:  { "enabled": true }
Response: 200 { "id": "<certId>", "revocationDeepCheck": true }
Auth: org-scoped; ENGINEER+ (mirror TargetController scan-trigger auth).
Errors: 404 ProblemDetail (cert not in org), 403 ProblemDetail.
```

### Endpoint — per-org revocation settings (extend existing notification-settings surface, BE-12)
```
GET  /api/v1/organizations/{orgId}/notification-settings
PUT  /api/v1/organizations/{orgId}/notification-settings
Request/response add:
  { "revocationCheckEnabled": true, "revocationFailMode": "SOFT", "alertOnUntrustedChain": false, ... existing fields }
```

### Status enum the FE must render
`VALID, EXPIRING, EXPIRED, UNREACHABLE, UNKNOWN, REVOKED, INVALID` — plus the derived `onHold` modifier on `REVOKED`.

---

# BACKEND WORK PACKAGE (backend-engineer)

> Guardrail: **BE-6 touches the agent module** — plain Java 25, **no Spring, no Lombok, no heavy deps**. The only agent change is one `List<String> chainB64` field + base64 encoding in `SslScanner`. Everything else is server-side.

### BE-1 — Flyway migration: enum values (standalone, non-transactional)
- **Create:** `server/src/main/resources/db/migration/V34__add_cert_status_revoked_invalid.sql`
  ```sql
  ALTER TYPE cert_status ADD VALUE IF NOT EXISTS 'INVALID';
  ALTER TYPE cert_status ADD VALUE IF NOT EXISTS 'REVOKED';
  ```
- **Create sidecar:** `V34__add_cert_status_revoked_invalid.sql.conf`
  ```
  executeInTransaction=false
  ```
  Rationale: `ALTER TYPE ... ADD VALUE` cannot run inside a transaction (PG restriction). `FlywayConfig.java` uses `validateOnMigrate(true)` — keep this enum-add isolated.
- **Acceptance:** app start / `flyway:migrate` succeeds on a prod-schema copy; `SELECT enum_range(NULL::cert_status)` includes `REVOKED` and `INVALID`; idempotent.

### BE-2 — Flyway migration: columns (transactional)
- **Create:** `server/src/main/resources/db/migration/V35__add_revocation_chain_columns.sql`
  ```sql
  ALTER TABLE certificate_records
    ADD COLUMN revocation_status            text,
    ADD COLUMN revocation_source            text,
    ADD COLUMN revocation_reason            text,
    ADD COLUMN revocation_reason_code       smallint,
    ADD COLUMN revoked_at                   timestamptz,
    ADD COLUMN revocation_checked_at        timestamptz,
    ADD COLUMN last_revocation_alert_sent_at timestamptz,
    ADD COLUMN chain_trusted                boolean,
    ADD COLUMN chain_validation_error       text,
    ADD COLUMN revocation_deep_check        boolean NOT NULL DEFAULT false;

  ALTER TABLE notification_settings
    ADD COLUMN revocation_check_enabled     boolean NOT NULL DEFAULT true,
    ADD COLUMN revocation_fail_mode         text    NOT NULL DEFAULT 'SOFT',
    ADD COLUMN alert_on_untrusted_chain     boolean NOT NULL DEFAULT false;

  CREATE INDEX IF NOT EXISTS idx_cert_records_revocation_recheck
    ON certificate_records (revocation_checked_at)
    WHERE status NOT IN ('EXPIRED','UNREACHABLE');
  ```
- **Acceptance:** migration applies cleanly; `ddl-auto: validate` passes against the BE-3 entities; all new columns nullable/defaulted (safe online add).

### BE-3 — Enum + entity changes
- **`enums/CertStatus.java:2`** → `{ VALID, EXPIRING, EXPIRED, UNREACHABLE, UNKNOWN, REVOKED, INVALID }`.
- **`entity/CertificateRecord.java`** (after `:84`): add `revocationStatus`, `revocationSource`, `revocationReason`, `revocationReasonCode` (Integer), `revokedAt` (Instant), `revocationCheckedAt` (Instant), `lastRevocationAlertSentAt` (Instant), `chainTrusted` (Boolean), `chainValidationError` (String), `revocationDeepCheck` (boolean default false). Model `revocationStatus`/`revocationSource`/`chainValidationError` as `@Enumerated(EnumType.STRING)` Java enums backed by `text` (NOT new PG enums).
- **`entity/NotificationSettings.java`** (after `:50`): add `revocationCheckEnabled` (Boolean default true), `revocationFailMode` (enum `SOFT|HARD` default SOFT), `alertOnUntrustedChain` (Boolean default false).
- **`service/ExpiryEvaluationService.Settings` record (`:59`)** + `toSettings`/`appYmlFallback` (`:281-287`): carry the three new policy fields through the per-target → org → app.yml resolution chain (`:246-264`).
- **Acceptance:** compiles; `ddl-auto: validate` green; existing RFC 0008 tests pass.

### BE-4 — `ChainValidationService` (new `@Service`)
- Create + `ChainValidationResult { boolean trusted; ChainValidationError error; int chainDepth; }`.
- `CertPathValidator.getInstance("PKIX")` against `TrustAnchor`s from `app.chain.trust-store` (`SYSTEM` → JDK cacerts; path → load `KeyStore`).
- Input: full `X509Certificate[]`. If intermediates missing and `app.chain.complete-via-aia=true`, attempt AIA `caIssuers` fetch (SSRF-guarded, share egress client with BE-5).
- Map failures to **§5.1 codes** exactly: `UNTRUSTED_ANCHOR`, `INCOMPLETE_CHAIN`, `SELF_SIGNED`, `EXPIRED_CHAIN_ELEMENT`, `PATH_LEN_VIOLATION`, `NAME_CONSTRAINT_VIOLATION`, `BASIC_CONSTRAINT_VIOLATION`, `SIGNATURE_INVALID`, `WEAK_ALGORITHM`, `CHAIN_ERROR:<reason>`.
- Service computes `trusted`/`error` only — public/private leniency is applied in BE-7 (chain failure → `INVALID` only on public targets).
- **Acceptance:** unit tests per error code; known-good public chain → `trusted=true`; self-signed leaf → `SELF_SIGNED`.

### BE-5 — `RevocationCheckService` (new `@Service`)
- Create + `RevocationResult { RevocationStatusEnum status; RevocationSource source; String reason; Integer reasonCode; Instant revokedAt; Instant checkedAt; }`.
- Cascade **OCSP-stapling → OCSP → CRL**, **stop at first definitive**. If `revocation_deep_check=true`, **query both**; any definitive REVOKED wins (sticky), log discrepancy.
- **Mandatory signature verification** on every OCSP response and CRL (issuer key or delegated OCSP-signing EKU `1.3.6.1.5.5.7.3.9`). Unverifiable → `UNKNOWN`.
- **Reason mapping** per §5.3 incl. `certificateHold(6)` → `CERTIFICATE_HOLD`; `removeFromCRL(8)` clears a prior hold (`GOOD`). Re-check may move `REVOKED(hold) → GOOD`.
- **Soft-fail default:** unreachable/timeout/unknown/stale/bad-signature → `UNKNOWN`.
- **Security guards (implement now):** SSRF (only http/https; block private/loopback/link-local AIA/CDP), CRL `max-bytes` cap, per-fetch timeouts, bounded redirects, dedicated bounded HTTP pool, reject stale OCSP (`nextUpdate` past) + clock-skew tolerance.
- JDK `PKIXRevocationChecker` where sufficient; BouncyCastle (already on classpath) for OCSP parse/verify where the JDK API is thin.
- **Acceptance:** fixtures for GOOD, REVOKED(keyCompromise), REVOKED(certificateHold), unknown, stale, bad-signature, no-AIA/no-CDP, CRL-revoked; SSRF guard rejects CDP at `127.0.0.1`/`10.0.0.0/8`.

### BE-6 — Agent wire contract (chain delivery) — **AGENT MODULE, guardrail applies**
- **`agent/.../model/ScanResult.java`** (`:26-27` region): add `private List<String> chainB64;` + getter/setter (plain Java, no Lombok).
- **`agent/.../scanner/SslScanner.java` `full(...)` (`:283-301`):** populate `chainB64` from the `chain` array — leaf at index 0 then intermediates — `Base64.getEncoder().encodeToString(cert.getEncoded())`. **DELTA path (`:303-312`) leaves `chainB64` null.**
- **Server `AgentScanResultRequest`** (consumed `service/AgentService.java:235-244`): add optional `List<String> chainB64`.
- **Backward compatible:** older agents omit it → server treats chain as `INCOMPLETE_CHAIN` (soft) or completes via AIA.
- **Acceptance:** agent compiles with no new dependency in `agent/pom.xml`; FULL scan serializes leaf-first `chainB64`; server deserializes; missing field tolerated.

### BE-7 — Status convergence (single point) + wire into scan paths + stop discarding chain
- **`service/ExpiryEvaluationService.determineCertStatus` (`:155-161`)** accepts `RevocationResult` + `ChainValidationResult` + `Target`, applies precedence:
  ```
  if revocation.status == REVOKED            -> REVOKED      // incl. on-hold (reversible)
  if days < 0                                -> EXPIRED
  if chain.trusted == false && target.public -> INVALID      // private chain failures are advisory, not INVALID
  if days <= warningDays                     -> EXPIRING
  else                                       -> VALID
  ```
  Keep this the **only** status authority.
- **Server path — `service/SslScannerService.java`:** stop discarding `chain[1..n]` at `:132`; pass full `X509Certificate[]` into BE-4/BE-5; set `chainDepth`; replace status call at `:150`; persist all BE-3 fields; keep `evaluateAndNotify(...)` at `:155`.
- **Agent path — `service/AgentService.java`:** `processFull` (`:224-252`) build chain from `chainB64`+`publicCertB64`, run BE-4/BE-5, replace status at `:245`. `processDelta` (`:254-270`) run revocation on the **stored** `publicCertB64`, replace status at `:264`.
- **Acceptance:** revoked-but-not-expired → `REVOKED`; expired+revoked → `REVOKED`; public untrusted-chain in-date → `INVALID`; private untrusted-chain in-date → `VALID` with `chain_trusted=false`.

### BE-8 — `RevocationRecheckScheduler` (new, schedulers package)
- Mirror `CertificateExpiryScheduler.java:51-55`: `@Scheduled(cron="${app.revocation.recheck.schedule-cron:0 0 4 * * *}")`, `@SchedulerLock(name="RevocationRecheckScheduler_recheck", lockAtMostFor="PT1H", lockAtLeastFor="PT10M")`, `@Transactional`.
- Eligible (uses BE-2 index): `status NOT IN (EXPIRED,UNREACHABLE)` AND (`revocation_checked_at IS NULL` OR `< now - app.revocation.recheck.min-age-hours`). Paged `app.revocation.recheck.batch-size` (default 500), org-fair.
- Per cert: decode stored `publicCertB64`, complete chain, run BE-5, converge (BE-7), transition-gated alert via AFTER_COMMIT (BE-9).
- **Acceptance:** previously-GOOD stored cert now revoked → `REVOKED` with no rescan; held cert later removed-from-CRL → reverts to expiry-derived.

### BE-9 — Notification path (REVOKED critical + chain advisory + hold)
- **REVOKED alert:** CRITICAL, **bypasses expiry dedup** (`ExpiryEvaluationService.java:187-194`), **transition-gated** via new `last_revocation_alert_sent_at` (mirror `last_alert_sent_at` at `entity/CertificateRecord.java:83-84`); fire only on edge into REVOKED (or when stamp null). Reuse AFTER_COMMIT dispatch (`:225-235`); pre-resolve in-transaction (avoid `LazyInitializationException`, `:217-222`).
- **Chain advisory:** low severity. **Public → always; private → only if `alert_on_untrusted_chain=true`.**
- **Hold:** `revocationReasonCode==6` → alert copy "Suspended (on hold)", reversible; not terminal.
- **KEY_COMPROMISE / CA_COMPROMISE:** emphasize prominently.
- Extend `ExpiryAlertContext` (`dto/internal/`) or add `RevocationAlertContext` carrying reason/source/onHold.
- **Acceptance:** one alert per REVOKED transition (not per re-check); hold reversal no spam; chain advisory respects gating.

### BE-10 — Transition-event emission (history deferred, events now)
- On every revocation-involving transition (GOOD↔REVOKED, hold→good, chain trusted↔untrusted), publish `CertRevocationTransitionEvent` (certId, orgId, old/new status, reason, source, timestamp).
- **Subscribers now:** WARN logger + Micrometer counter only. No DB table (§4.4 deferred). Seam lets the future history table subscribe without touching producers.
- **Acceptance:** event fires only on transitions; metric increments; no persistence added.

### BE-11 — Config keys (§7.1) + Micrometer metrics (§7.2)
- Add the full `app.revocation.*` + `app.chain.*` block from RFC §7.1 to `application.yml` (env-overridable), incl. `app.revocation.shadow=true` for rollout.
- Metrics: `certguard.revocation.check.total{source,result}`, `...duration`, `certguard.revocation.responder.failure.total{source,reason}`, `certguard.chain.validation.total{result}`, `certguard.revocation.revoked.total{reason}`.
- **Acceptance:** keys resolve; metrics at actuator after a scan; shadow mode records fields but never sets REVOKED/INVALID nor alerts.

### BE-12 — REST endpoints (deep-check toggle + notification-settings extension)
- **Deep-check PATCH:** add `PATCH /api/v1/organizations/{orgId}/certificates/{certId}/revocation-deep-check` to `CertificateController.java` (org-scoped, ENGINEER+ — mirror scan-trigger auth on `TargetController`). Body `{ "enabled": bool }`; persists `revocationDeepCheck`; returns `{ id, revocationDeepCheck }`. 404/403 as ProblemDetail.
- **Cert response DTO:** ensure all BE-3 fields + derived `onHold` are serialized in the existing certificate list/detail responses (per contract).
- **Notification-settings DTO/controller:** extend the existing notification-settings GET/PUT with `revocationCheckEnabled`, `revocationFailMode`, `alertOnUntrustedChain`.
- **Acceptance:** PATCH persists across reload + enforces org scope; cert responses carry the new fields incl. `onHold`; settings round-trip the three new fields with correct defaults.

---

# FRONTEND WORK PACKAGE (frontend-engineer)

> **Correct UI location: `SSLCertificateManager/ui/`** (the real, build.sh-built SPA). **Do NOT use `/home/msuman/git/certguard-ui` — stale decoy.** Discover component paths under `ui/src/`.

### FE-0 — Pre-req: confirm the Cross-team API contract is frozen
- Don't start integration until BE confirms the contract (at least shadow mode). New fields are additive to the existing cert response and notification-settings DTO.
- **Acceptance:** contract reviewed; a shadow-mode cert payload with new fields available in dev.

### FE-1 — New status rendering: `REVOKED`, `INVALID`, "Suspended (on hold)"
- Discover the existing status-badge component (renders `VALID/EXPIRING/EXPIRED/UNREACHABLE`) under `ui/src/`.
- Add **`REVOKED`** (critical — strongest red, shield-x/ban icon), **`INVALID`** (error styling, distinct, broken-link/triangle icon), and **"Suspended (on hold)"** when `status==REVOKED && onHold==true` (amber/orange = reversible, distinct from terminal red).
- Keep palette/iconography consistent.
- **Acceptance:** all seven statuses + on-hold render with distinct, contrast-checked colors + tooltips; on-hold visibly reversible, terminal REVOKED not.

### FE-2 — Certificate detail view: revocation + chain panel
- Add a section showing `revocationStatus`, `revocationSource`, `revocationReason` (**humanized**; strong callout for **KEY_COMPROMISE**/**CA_COMPROMISE**), `revokedAt`, `revocationCheckedAt` ("last checked", relative), `chainTrusted`, `chainValidationError` (humanized from §5.1 codes).
- When `revocationStatus` is `UNKNOWN`/`UNCHECKED`, show neutral "not confirmed" (don't imply healthy or revoked).
- **Acceptance:** revoked cert shows reason + revoked-at prominently; soft-fail UNKNOWN neutral; chain errors human-readable.

### FE-3 — Per-cert "Complete revocation check" (deep-check) toggle
- Toggle on cert detail bound to `revocationDeepCheck` → `PATCH .../certificates/{certId}/revocation-deep-check` `{ "enabled": bool }`. Optimistic update + revert on error; tooltip: "Queries both OCSP and CRL for this certificate (slower, more thorough)."
- **Depends on BE-12.** Don't ship until the route exists.
- **Acceptance:** toggling persists across reload; failure reverts + errors; disabled while in flight.

### FE-4 — Per-org settings UI
- In the org notification/settings screen, add **`revocationCheckEnabled`** (default ON) with an unmissable OFF warning: *"Disabling this means CertGuard will no longer detect revoked certificates for this organization."*
- If supported, also surface `revocationFailMode` (SOFT/HARD + help text) and `alertOnUntrustedChain`.
- Persist via extended `PUT .../notification-settings`.
- **Acceptance:** persists; OFF warning unmissable; defaults render ON/SOFT/false when unset.

### FE-5 — Dashboard / list filters & counts
- Add `REVOKED` + `INVALID` to status filters and count/summary widgets; REVOKED visually prioritized (surfaces high).
- Distinguish on-hold where filters allow (≥ tooltip; ideally a chip).
- **Acceptance:** filtering returns right rows; counts match backend; "0 revoked" empty-state graceful.
