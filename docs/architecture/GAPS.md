# CertGuard — Gaps, Discrepancies, and Risks

Anchored against the current source under `server/` and `agent/` (post commits 5e73c1d, 6631679, 46e524b, 3fccdde, 9c79e5b). Companion to `HLD.md` / `LLD.md`.

> **Path note:** The original GAPS.md and design docs still reference the snapshot paths `certguard-server-source-20260419-0737/` and `certguard-agent-source-20260419-0737/`. The live source is at `server/` and `agent/`. All line references in this doc use the live paths.

---

## 1. Gaps Now Closed

### R1 — mTLS is symbolic → **CLOSED**
`AgentCertificateAuthority.java` has been deleted. Agent registration no longer issues client certs. `SecureHttpClient.java:24` explicitly documents: "Authentication is provided by bearer agentKey + HMAC, not mTLS." `RegistrationService.java:14-17` confirms mTLS client certificates are no longer issued or stored. The symbolic-mTLS issue is resolved by removing the CA path entirely.

### R2 — Hostname verification disabled on agent → **CLOSED**
`SecureHttpClient.java:50-58` now uses `DefaultHostnameVerifier` when `certguard.server.cert-fingerprint` is configured; only falls back to `NoopHostnameVerifier` in dev/trust-all mode (which logs a WARN).

### R3 — `APP_DEV_MODE=true` default → **CLOSED**
`application.yml:65` defaults `app.dev-mode: false`. `docker-compose.yml:84` also defaults `APP_DEV_MODE=false`. `DevAuthController.java:16-19` is now gated by both `@Profile("dev")` and `@ConditionalOnProperty(app.dev-mode=true)`. `SecurityConfig.java:79-81` only permits `/api/v1/auth/dev-token` when `devMode=true`.

### R4 — Flyway `repair()` + `validateOnMigrate(false)` → **CLOSED**
`FlywayConfig.java:12-20` no longer calls `repair()` and no longer disables validation. Now uses `validateOnMigrate(true)` and `baselineOnMigrate(false)`.

### R5 — CORS wildcard with credentials → **CLOSED**
`SecurityConfig.java:121-127` now refuses to start with wildcard CORS in non-dev mode (`IllegalStateException`). `application.yml:79-85` requires `APP_CORS_ALLOWED_ORIGINS` to be an explicit allowlist.

### R7 — Agent job-claim race → **CLOSED**
`AgentScanJobRepository.java:29-38` adds `claimPendingJobsWithLock` using native `FOR UPDATE SKIP LOCKED`. `AgentService.java:142-152` uses it inside `pollJobs`.

### R8 — JWT revocation absent → **CLOSED**
`TokenRevocationService.java` provides Caffeine-cache + DB-backed revocation against `revoked_tokens` table. `JwtAuthenticationFilter.java:119-127` rejects revoked sessions with 401 before any downstream processing. Wired into member-removal flow at `TeamService.java:224`. JWT now also carries `jti`, requires `iss=certguard-cloud` and `aud=certguard-ui` (`JwtTokenProvider.java:44-72`). TTL reduced from 24h to 8h (`application.yml:78`).

### R10 — Error model inconsistency → **MOSTLY CLOSED**
`AgentAuthFilter.java:111-122` now writes RFC 9457 `application/problem+json` body. `SalesAuthFilter.java:119-129` (new) also uses ProblemDetail. `SecurityConfig.java:97-103` global entryPoint returns ProblemDetail JSON. Minor leaks remain (see D7, D8 below) but are dev-only paths.

### D3 — Heartbeat → Jobs ordering → **CLOSED**
`PollLoop.tick()` at `agent/src/main/java/com/certguard/agent/http/PollLoop.java:63-73` now performs heartbeat first, then `pollJobs()`, matching the HLD §3.4 sequence.

---

## 2. Gaps Still Open

### R6 — In-memory invitation OTP store (medium)
`InvitationService.java:47` still uses `ConcurrentHashMap`. The in-code TODO at lines 43-46 explicitly calls for Redis. Now more urgent: the server is designed for multi-replica deployment (ShedLock present throughout), meaning OTPs are lost on cross-node requests.

**Action:** Move to a Redis client or an `invitation_otp` DB table with TTL.

### R9 — RabbitMQ: never present in the live tree (doc drift) → **CLOSED (no-op)**

Earlier GAPS revisions claimed `docker-compose.yml:31-51` ran a RabbitMQ container and `pom.xml:90-92` pulled `spring-boot-starter-amqp`. Neither exists in the current source: `server/docker-compose.yml` has no rabbitmq service and `server/pom.xml` has no amqp dependency.

The platform's async needs are met by (a) the DB-as-durable-queue pattern for `agent_jobs` (`FOR UPDATE SKIP LOCKED`, `AgentJobRepository.java`) and (b) Spring `@Async` + virtual threads.

**Decision (RFC 0005, 2026-05-29):** No message broker is introduced. The `certguard-renewal-service` extraction uses synchronous internal REST + DB-backed CA-order polling (`ca_orders` table + `CaOrderPollScheduler`), not AMQP. HLD §5 deployment diagram should be updated to remove the stale `rabbitmq`/`rabbitmq_data` boxes.

### Transactional boundaries — partially open
- `CertificateService.java:21-23` has no `@Transactional` annotation of any kind. `listCertificates`, `getExpiring`, and `getDashboard` run outside any transaction; safe today because response mapping doesn't lazy-traverse `target.*`, but fragile.
- `MspClientService.java:23-26` also has no class-level `@Transactional`.

**Action:** Add class-level `@Transactional(readOnly = true)` to both; override write methods with `readOnly = false`.

### Admin-configurable session timeout — planned (low)
Session/JWT timeouts are currently fixed in config + code, not adjustable by org admins. Normal users get a 24h absolute TTL (`auth.jwt.expiration-ms` / `AUTH_JWT_EXPIRATION_MS`), platform admins get a non-expiring token (far-future exp, `UnifiedTokenProvider`), and the UI enforces a hard-coded 30-min idle timeout (`IDLE_TIMEOUT_MS`, warn at 29m) plus a server-validated on-navigation check (`POST /api/auth/validate`). See `project_session_timeout_policy` and HLD §7.9. Product intent (deferred, confirmed 2026-05-31) is to let an admin user configure the session/idle timeout — most naturally a per-org setting that the auth-service reads at token-mint time and the UI reads (e.g. via `/me` or org profile) for the idle timer.

**Action (future):** Decide scope (per-org vs platform-wide), add a persisted setting + admin UI, have `UnifiedTokenProvider` resolve TTL from it at mint time, and surface the idle-timeout value to the UI. Keep platform-admin exemption configurable. No work scheduled yet.

---

## 3. New Gaps — Architecture Drift Not Captured in HLD/LLD

These features are fully implemented but not reflected in any design document.

### N1 — Gateway / auth-service split is undocumented (HIGH)
`docker-compose.yml:107-146` adds an `auth-service` (RS256, JWKS at `/api/auth/.well-known/jwks.json`, separate `certguard_auth` DB). `docker-compose.yml:185-210` adds an API gateway in front of both services. `JwtAuthenticationFilter.java:66-117` trusts `X-CG-User-Id`, `X-CG-Org-Id`, `X-CG-Role`, `X-CG-Email`, `X-CG-Platform-Admin` headers injected by the gateway (gateway validates RS256; server trusts injected headers).

This is a fundamentally different topology than HLD §1/§2 portray.

**Action:** Rewrite HLD §1 system-context and §2 component diagram to show the gateway → auth-service → server triangle. Add a security note that header-trust depends on the gateway stripping any client-supplied `X-CG-*` headers. Add sequence diagram for gateway-authenticated requests.

### N2 — Platform-admin "act-as-org" impersonation is undocumented (high)
`JwtAuthenticationFilter.java:131-175` honors `X-Acting-As-Org` (PLATFORM_ADMIN only) and `X-Acting-As-Reason` (mandatory for write methods). `PlatformAdminAuditService.java` records every cross-org action async into a new `platform_admin_audit` table. `OrgAuditService.java` records per-org admin actions (member removal, etc.). Code references ADR-0007 at `JwtAuthenticationFilter.java:131` — document does not exist.

**Action:** Author `docs/architecture/adrs/0007-platform-admin-act-as-org.md`. Update HLD §6 security section.

### N3 — Encrypted-bundle agent provisioning is undocumented (high)
New classes: `AgentBundleService.java`, `AgentProvisionController.java` (`POST /api/v1/agents`, `GET /api/v1/agents/{id}/bundle`), `AgentBundleCrypto.java` (AES-256-GCM + Argon2id), `AgentInstallKey` entity, and agent-side `BundleUnsealer.java`. Agent now boots from a `bundle.cgb` file unsealed with an install key (`AgentMain.java:103-105`, `AgentConfig.java:73-128`). The one-time download URL expires via `BundleExpiredException` → 410.

LLD §4.1 "Agent registration" sequence is stale — it describes the old mTLS-CA flow.

**Action:** Replace LLD §4.1 with the new bundle-issuance (server) and bundle-unseal (agent) flows. Add `POST /api/v1/agents` and `GET /api/v1/agents/{id}/bundle` to the LLD §2 endpoint table.

### N4 — Internal Sales API surface is undocumented (medium)
`SalesAuthFilter.java` authenticates `/api/internal/v1/sales/**` using `X-Sales-Key-Id` + `X-Sales-Key` BCrypt-verified against a `sales_api_keys` table. `SecurityConfig.java:87` carves out `/api/internal/**` as a separate authenticated surface. Sales webhook configuration exists at `application.yml:68-73` (`SALES_WEBHOOK_URL`, `SALES_WEBHOOK_SECRET`). None of this appears in HLD or LLD.

**Action:** Add HLD §2 internal-API surface note; add LLD §2 endpoint table rows.

### N5 — Subscription-suspended enforcement is undocumented (medium)
`SubscriptionGuard.java` blocks scan-triggering when subscription status is `SUSPENDED`, throwing `SubscriptionSuspendedException` → 403 with a typed problem URI (`GlobalExceptionHandler.java:53-59`). `BundleExpiredException` → 410 is similarly undocumented. Neither appears in LLD §6 error table.

**Action:** Add both exception types to LLD §6.

### N6 — ShedLock distributed scheduling is wired (medium)
`pom.xml:170-180` adds `shedlock-spring` + `shedlock-provider-jdbc-template`. `@SchedulerLock` is present on every scheduled job: `CertificateExpiryScheduler.java:59`, `AgentOfflineScheduler.java:41`, `AgentService.resetStaleClaimedJobs:304`, `AgentService.cleanupExpiredTokens:321`, `SslScannerService.scheduledPublicScan:46`. The presence of ShedLock confirms multi-instance deployment intent, which makes R6 (in-memory OTP store) genuinely urgent.

**Verify:** Confirm a `@Configuration` class exposes a `LockProvider` bean and a `shedlock` table is created by a Flyway migration — without these, `@SchedulerLock` is silently a no-op (see R12 below).

### N7 — RFC 0001 (member offboarding) implemented but undocumented (low)
`TeamService.revokeMember:150-251` enforces self-removal block, last-admin guard, org-admin-cannot-remove-other-admin guard, pending-invitation cancellation, JWT revocation, dual audit trail, and email notification. Commit 6631679 references "RFC 0001" but no `docs/architecture/rfcs/0001-*.md` exists.

**Action:** Author `docs/architecture/rfcs/0001-member-offboarding.md` consolidating the Phase 1 + Phase 2 design.

### N8 — Cert-expiry alert deduplication is undocumented (low)
`CertificateExpiryScheduler.java:44,67-92` adds `app.alert.dedup-hours` (default 23h) and persists `last_alert_sent_at` per record. Not in HLD §3.5 or LLD §5. **Note:** the dedup mechanism is also currently *broken* — see N11.

### N9 — Virtual threads enabled by default (informational)
`application.yml:4-6` sets `spring.threads.virtual.enabled: true` (Java 25 LTS). Worth noting in HLD §6 Scalability.

### N10 — Platform-admin `AdminController` is undocumented (low)
`AdminController.java` at `/api/v1/admin/**` (class-level `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`) consolidates flat list, tree, detail, promote/demote MSP, archive/restore, quota update, and audit feed. Endpoint table in LLD §2 does not list any `/api/v1/admin/**` routes.

### N11 — Cert-expiry alert dedup stamp is broken: `@Async`+boolean (HIGH)
`NotificationService.dispatchExpiryAlert` (`NotificationService.java:93-127`) is `@Async` **and** returns `boolean`; `@EnableAsync` is active (`CertGuardApplication.java:9`). The caller consumes the return synchronously (`CertificateExpiryScheduler.java:88-92`), but the async proxy returns before the body runs, so `certRepository.stampAlertSentAt(...)` (`CertificateRecordRepository.java:63-65`) is never reached → `last_alert_sent_at` is never written → the N8 dedup gate never trips → every in-window cert re-alerts on every daily run (alert storm), and `alertsSent` always logs 0. Fix in RFC 0008 §4 (return `void`; stamp in-transaction; dispatch via AFTER_COMMIT).

### N12 — No post-scan expiry-notification hook (HIGH)
Neither scan write-path evaluates expiry or notifies: `SslScannerService.persistCertificates:117-149` and `AgentService.processFull/processDelta:208-244` only set `CertStatus` and stamp `lastScannedAt`. Expiry alerts fire **only** from the 08:00 cron (`CertificateExpiryScheduler`), so a manual/force scan never notifies until the next morning. Closed by RFC 0008 §2/§7 (`ExpiryEvaluationService` convergence point called from sweep + both scan paths).

### N13 — Expiry thresholds are not tenant-scoped (MEDIUM)
`warning-days`/`critical-days` are app-wide `@Value` config (`application.yml:98-100`), read in `CertificateExpiryScheduler:42-44`, `SslScannerService:39-40`, and **hardcoded `30`** in `AgentService.determineStatus:361-366` (a divergence). No per-org/per-target override. Closed by RFC 0008 §3 (`notification_settings` table + resolution chain).

### N14 — No daily scan sweep for private/agent targets (MEDIUM) — **Closed**
Public targets are scanned nightly by `SslScannerService.scheduledPublicScan` (cron `0 0 2 * * *`). Private/agent targets had no timer-driven sweep — they were only scanned on manual trigger or when an agent happened to poll a user-queued job. This meant the "all registered certificates refreshed every 24h" contract did not hold for private targets, so the expiry sweep at 08:00 could be working from stale cert data for private targets. Closed by RFC 0008 §6: `PrivateScanScheduler` (`PrivateScanScheduler.java`) — `@Scheduled(cron = "${app.scanning.private.schedule-cron:0 0 3 * * *}")`, ShedLock `lockAtMostFor=PT1H`, paged enqueue in chunks of `app.scanning.private.enqueue-batch-size` (default 500). Eligible targets: `enabled=true AND isPrivate=true AND agent IS NOT NULL`. Per-target failures (suspended org, missing agent race) are caught and logged without aborting the sweep.

### N15 — No certificate chain validation or revocation checking; `REVOKED`/`INVALID` status unrepresentable (HIGH)
Both scanners install a trust-all `X509TrustManager` (`agent/src/main/java/com/certguard/agent/scanner/SslScanner.java:196-202`; `server/src/main/java/com/certguard/service/SslScannerService.java:107-111`) and read **leaf fields only** (`SslScanner.java:283-301`; `SslScannerService.java:129-167`). The full chain reaches the socket (`getPeerCertificates()` at `SslScanner.java:250`, `SslScannerService.java:124`) but `chain[1..n]` is discarded. There is **no** OCSP-stapling inspection, OCSP request, CRL fetch/parse, AIA/CDP handling, or `PKIXRevocationChecker` anywhere in the tree. `CertStatus` (`enums/CertStatus.java:2`) has no `REVOKED` or `INVALID` value, and the sole status authority `ExpiryEvaluationService.determineCertStatus` (`:155-161`) is pure date math — it can never produce either.  Agent wire contract (`agent/src/main/java/com/certguard/agent/model/ScanResult.java`) carries no chain or revocation fields.

**Impact (confirmed in production):** CertGuard's own cloud cert `cloud.oopsssl.co.uk` was revoked by Let's Encrypt for `keyCompromise`; browsers show `NET::ERR_CERT_REVOKED`; CertGuard reported it `VALID` because it had not yet expired.

**Action:** Implement RFC 0009 — `ChainValidationService` + `RevocationCheckService` (server-side; OCSP-stapling → OCSP → CRL, soft-fail default), `RevocationRecheckScheduler` (daily, ShedLock), add `REVOKED` and `INVALID` to the `cert_status` enum (standalone non-transactional Flyway migration) + revocation/chain columns on `certificate_records`, and a `chainB64` field on the agent scan result so the server can build the full chain offline. Status precedence `REVOKED > EXPIRED > INVALID > EXPIRING > VALID`. **Decided (2026-06-20):** expired-and-revoked resolves to `REVOKED` (RFC §10.3); an untrusted/invalid chain on a **public** target fails to `INVALID` and raises an advisory, while private targets stay advisory-only (RFC §10.4).

---

## 4. Doc-vs-Code Discrepancies

| # | Area | Doc says | Code says | Action |
|---|---|---|---|---|
| D1 | PostgreSQL version | `CLAUDE.md` references Postgres 16 | `docker-compose.yml:7` uses `postgres:15-alpine` | Align both on one version |
| D2 | RabbitMQ in HLD | HLD §2 shows RabbitMQ as a live component | No consumer/publisher in server source | Update HLD diagram or implement (see R9) |
| D3 | Design doc paths | HLD/LLD reference `certguard-server-source-20260419-0737/...` | Live source is at `server/` and `agent/` | Re-anchor line references in design docs |
| D4 | Invitation endpoint location | LLD §2 lists it under OrgController | Lives on `TeamController.java:34` | Update LLD §2 table |
| D5 | UI architecture | HLD describes full React SPA | UI is now a separate service not in this repo | Update HLD §2 system boundary; remove UI detail from this doc |
| D6 | Agent JAR delivery | LLD §4 describes `/agent/download` from classpath | `application.yml:122` uses `app.agent.artifact-url-template` (GHCR-style) with classpath fallback | Update LLD §4 |
| D7 | `AgentController.register` error body | All errors should be ProblemDetail | Malformed `X-Org-Id` returns `ResponseEntity.badRequest().build()` with no body (`AgentController.java:133-134`) | Minor; fix for uniformity |
| D8 | DevAuthController error shape | ProblemDetail everywhere | Returns `Map.of("error", ...)` for unknown role (`DevAuthController.java:40`) | Dev-only; low priority |

---

## 5. Architectural Risks (current)

| # | Risk | Severity | Status |
|---|---|---|---|
| R1 | mTLS was symbolic | ~~high~~ | **Closed** — mTLS removed; bearer+HMAC is the design |
| R2 | Hostname verification disabled | ~~high~~ | **Closed** — DefaultHostnameVerifier when fingerprint set |
| R3 | `app.dev-mode=true` default | ~~high~~ | **Closed** — default flipped; DevAuthController double-gated |
| R4 | Flyway repair + no validation | ~~medium~~ | **Closed** — validateOnMigrate(true), no repair() |
| R5 | CORS wildcard + credentials | ~~medium~~ | **Closed** — explicit allowlist, startup refuses wildcard in prod |
| R6 | In-memory OTP store | medium | **Open** — `InvitationService.java:47`; urgent with multi-replica |
| R7 | Job-claim race | ~~medium~~ | **Closed** — `FOR UPDATE SKIP LOCKED` |
| R8 | JWT revocation absent | ~~medium~~ | **Closed** — Caffeine + DB revocation, filter-checked |
| R9 | RabbitMQ provisioned, unused | low–medium | **Open** — decide adopt vs remove |
| R10 | Error model inconsistency | ~~low~~ | **Mostly closed** — minor leaks in dev-only paths |
| R11 | UI fragility | ~~medium~~ | **N/A** — UI is a separate service |
| R12 (new) | ShedLock LockProvider may not be wired | **HIGH** | **Verify** — if missing, all `@SchedulerLock` annotations are silently no-ops |
| R13 (new) | `CertificateService` / `MspClientService` missing `@Transactional` | low | **Open** — works today but fragile |
| R14 (new) | Gateway/auth-service split undocumented | medium | **Open** — see N1 |
| R15 (new) | Bundle download single-use token replay protection | medium | **Verify** — check `AgentBundleService` for atomic consume |
| R16 (new) | No chain validation / revocation checking; revoked cert reported VALID | **HIGH** | **Open** — see N15; RFC 0009 (revoked `cloud.oopsssl.co.uk` went undetected) |

---

## 6. Prioritised Recommendations

### P0 — verify before next release
1. **Confirm ShedLock `LockProvider` bean and `shedlock` table in a Flyway migration.** Without these, every `@SchedulerLock` annotation is silently skipped and multi-replica deploys will double-fire all schedulers (R12).
2. **Update HLD §1/§2 to show gateway + auth-service topology** and document the `X-CG-*` header-trust security contract (N1 / R14).
3. **Confirm bundle download token is atomically consumed** (mark used before returning the bundle, not after) to prevent replay (R15).

### P1 — correctness
4. Move OTP store to Redis or a DB-backed `invitation_otp` table with TTL (R6).
5. Add class-level `@Transactional(readOnly = true)` to `CertificateService` and `MspClientService` (R13).
6. Decide RabbitMQ fate — implement consumer for scan-dispatch fan-out, or remove dependency and container (R9).
7. Align Postgres version across compose and `CLAUDE.md` (D1).

### P2 — hygiene
8. Author `docs/architecture/adrs/0007-platform-admin-act-as-org.md` (N2).
9. Author `docs/architecture/rfcs/0001-member-offboarding.md` (N7).
10. Replace LLD §4.1 agent-registration sequence with bundle flow; add new endpoints to LLD §2 table (N3, D6).
11. Add `BundleExpiredException` → 410 and `SubscriptionSuspendedException` → 403 to LLD §6 error table (N5).
12. Convert `AgentController.register` malformed-header response to `ProblemDetail` (D7).
13. Re-anchor HLD/LLD path references from snapshot folder names to `server/` and `agent/` (D3).

---

## 7. Open Questions

- Is the gateway + auth-service split documented in a sibling repo, or does it need to be pulled into these docs? (N1)
- Is RabbitMQ intended for Phase-3 scan dispatch, or will it be removed? (R9)
- Should ENGINEER-role users be able to mint agent registration tokens and revoke agents, or should that be ADMIN+ only? (`AgentController.java:49,119`)
- Should the bundle download URL be additionally rate-limited at the nginx layer, or is in-app TTL + single-use token sufficient? (R15)
- Is true mTLS (client-auth at TLS layer) still a roadmap goal, or is bearer+HMAC+pinned-TLS the permanent design? (R1 closed)
