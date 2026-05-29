# CertGuard — Product Requirements Document

> Scope: this PRD describes the product **as it is implemented in code today**.
> It is not a roadmap. Every section anchors to the source.
>
> Path note: live source is under `server/` and `agent/`. Older doc citations
> use `certguard-server-source-20260419-0737/` / `certguard-agent-source-20260419-0737/`
> as logical prefixes — treat them as equivalent to `server/` and `agent/`.

## 1. Product Overview

CertGuard is a multi-tenant SaaS platform for TLS/SSL certificate discovery,
inventory, expiry alerting, and lifecycle visibility across public and private
network targets. It is delivered as a **two-process system**:

- A **cloud server** (`server/`) — a Spring Boot 4.0.x / Java 25 LTS application
  that owns multi-tenant data, RBAC, the REST API, schedulers, and direct scans
  of internet-reachable targets. Operated as three containers behind nginx: the
  gateway (`certguard-gateway`), the auth service (`certguard-auth-service`), and
  the core server (`app`), wired in `server/docker-compose.yml`.
- A **self-hosted agent** (`agent/`) — a plain Java JAR (no Spring) that customers
  run inside their private networks to scan TLS endpoints the cloud cannot reach.
  Installed via an encrypted bundle; speaks to the server on an HTTPS poll loop
  with bearer key + HMAC-signed results.

**Target users:**

| User | Description |
|---|---|
| Platform admins (SaaS operator) | `PLATFORM_ADMIN` users who run the service: provision orgs, archive/restore, set quotas, impersonate a customer org for support, review the cross-org audit feed. Identified by JWT claim `platformAdmin: true`. |
| Org admins | `ADMIN` org members. Invite/remove members, manage targets, agents, locations, settings. |
| Engineers | `ENGINEER` org members. Manage targets and agents; cannot invite or remove members. |
| Viewers | `VIEWER` org members. Read-only. |
| MSP operators | Staff at a parent (`OrgType.MSP`) organisation who manage child orgs through the parent's session. |

## 2. User Roles & Permissions

### 2.1 Per-org role (`org_members.role` → `OrgMemberRole`)

| Role | Capability |
|---|---|
| `ADMIN` | All org-scoped actions: targets, agents, locations, members, invites, role changes, removals (subject to RFC-0001 guards). |
| `ENGINEER` | Provision agents (`POST /api/v1/agents`), manage targets, view certificates. Cannot manage members. |
| `VIEWER` | Read-only. |

### 2.2 RBAC enforcement points

- **Controller-level** `@PreAuthorize("hasRole(...)")` / `hasAnyRole(...)` on every non-public endpoint.
- **Filter-level** — `JwtAuthenticationFilter` rejects revoked sessions (`JwtAuthenticationFilter.java:119-127`) and enforces the `X-Acting-As-Org` / `X-Acting-As-Reason` contract (`JwtAuthenticationFilter.java:131-175`; see ADR-0007).
- **Service-level defense-in-depth** — `TeamService.revokeMember` re-verifies the caller is an accepted ADMIN of the org even though the controller already required it.
- **Tenant scoping** — every read flows through `TenantContext.getOrgId()` / `TenantContext.getAccessibleOrgIds()` populated from the trusted principal, so an authenticated user cannot reach another tenant's rows even with a matching URL `orgId`.

## 3. Core Features

### 3.1 Certificate discovery

- **Public direct scan.** `SslScannerService.scheduledPublicScan` scans all `Target` rows with `is_private = false` on cron `0 0 2 * * *` (default), using `fetchCertificateChain` with SNI, a trust-all `X509TrustManager`, and up to 3 retries with linear backoff. Retry marks the target `UNREACHABLE` after exhaustion. Protected by ShedLock.
- **Agent-based scan.** Private targets are scanned by the customer's own agent via the `agent_scan_jobs` queue (poll loop with `FOR UPDATE SKIP LOCKED` claim; see §3.5).

### 3.2 Certificate inventory

- **Targets** (`Target` entity, `/api/v1/targets`) — host/port pair scoped to an org. Carries `is_private`, optional `agent_id`, optional `location_id`, JSONB `tags`, JSONB `notification_channels`.
- **Certificate records** (`CertificateRecord` entity) — per-target observed cert: issuer, serial, expiry, SANs, key info, status (`VALID / EXPIRING / EXPIRED / UNREACHABLE / UNKNOWN`).
- **Dashboard** — `GET /api/v1/dashboard` returns aggregate counts and upcoming expiries.

### 3.3 Certificate expiry alerting

- `CertificateExpiryScheduler.checkExpiringCertificates` runs on cron `0 0 8 * * *` (default). Per-org, per-cert, it dispatches via `NotificationService.dispatchExpiryAlert(target, daysLeft, severity)` `@Async`. Severity thresholds: `app.alert.warning-days` (default 30) and `app.alert.critical-days` (default 7).
- Alert deduplication: `CertificateRecord.lastAlertSentAt` + `app.alert.dedup-hours` (default 23h) prevents re-alerting within the same day. `CertificateExpiryScheduler.java:100-105`.
- Email send is skipped when `APP_DEV_MODE=true` or when the target's `notification_channels.email` is not set.

### 3.4 Agent provisioning (bundle-based)

- Org admin/engineer POSTs to `POST /api/v1/agents` with `agentName`, `allowedCidrs[]`, `maxTargets`, optional `locationId`.
- Server creates a `PENDING` `Agent` row, a BCrypt-hashed `AgentRegistrationToken` bound to that agent id, generates a 125-bit install key (`"CGK-" + base32`), derives an Argon2id wrapping key, AES-256-GCM-seals the bootstrap payload (`bundle.cgb`), and returns `{agentId, installKey, bundleDownloadUrl, expiresAt}` exactly once. Code: `server/src/main/java/com/certguard/service/AgentBundleService.java:102-199`.
- `GET /api/v1/agents/{id}/bundle?dlToken=...` is single-use. The token is atomically consumed before the ZIP is built. Subsequent calls return 410 Gone via `BundleExpiredException`.
- On first boot, the agent's `BundleUnsealer` reads `bundle.cgb`, prompts for the install key, Argon2id-derives the decryption key, AES-GCM-decrypts, persists the bootstrap properties, and triggers `POST /api/v1/agent/register`. Code: `agent/src/main/java/com/certguard/agent/security/BundleUnsealer.java:69-266`.
- Hourly `cleanupExpiredInstallKeys` purges unused install key rows under a ShedLock guard.

### 3.5 Agent management

- **Heartbeat / offline detection.** Every authenticated agent request updates `agents.last_seen_at` in `AgentAuthFilter`. `AgentOfflineScheduler` flips agents inactive when `last_seen_at` is older than `AGENT_OFFLINE_THRESHOLD_MINUTES` (default 10) on a 5-minute cadence and emails the org contact.
- **Job claim.** `AgentScanJobRepository.claimPendingJobsWithLock` uses native `FOR UPDATE SKIP LOCKED` to prevent double-claim across concurrent agents. `AgentScanJobRepository.java:29-38`.
- **Stale-job reclaim.** `AgentService.resetStaleClaimedJobs` resets `CLAIMED` jobs older than 10 minutes back to `PENDING` every 5 minutes. ShedLock-protected.
- **HMAC-signed results.** Every `POST /api/v1/agent/results` body is signed by `HmacSigner` over `targetId:scanType:serialNumber:notAfterMs`. Verified server-side with constant-time compare by `AgentHmacService`.
- **FULL vs DELTA modes.** `AgentScanResultRequest.scanType` discriminates: FULL carries full cert attributes for upsert; DELTA carries only `certificateId` + new `notAfter` for a "still valid" refresh.

### 3.6 Multi-tenancy

- Every row is scoped to `org_id`. Child tables denormalize `org_id` alongside the foreign key (e.g., `certificate_records.org_id`, `agent_scan_jobs.org_id`) for query performance.
- URL hierarchy reflects the tenant: `/api/v1/org/...`, `/api/v1/admin/orgs/{orgId}/...`.
- `TenantContext` is a ThreadLocal populated from the trusted principal and cleared in the filter's finally block (`JwtAuthenticationFilter.java:178-220`).

### 3.7 MSP support

- `Organization.orgType` is `SINGLE` or `MSP`. An MSP carries no `parent_org_id`; child orgs reference it via `parent_org_id`.
- On every request, if the caller's org is `MSP`, the filter loads its active child org ids into `TenantContext.accessibleOrgIds` (`JwtAuthenticationFilter.java:184-192`).
- `MspClientController` exposes `/api/v1/msp/clients[/{id}]` for managing child orgs.

### 3.8 Member management

- **Invite.** `POST /api/v1/org/invitations` (ADMIN/PLATFORM_ADMIN). Generates a base64url 32-byte token, stores its SHA-256 hash, persists an `Invitation` row with 24h TTL, sends the email after commit. Rejects inviting a PLATFORM_ADMIN as an org member. Rejects re-inviting an existing ACCEPTED member. Revokes stale pending invites for the same email before issuing a new one.
- **Accept (OTP).** `POST /api/v1/auth/invite/accept` validates the token hash and a separately-emailed OTP code (BCrypt-hashed OTP stored in `invitation_otp` DB table), creates/updates the user, and inserts the `org_members` row.
- **Remove (RFC-0001).** `DELETE /api/v1/org/members/{userId}` enforces: self-removal block, last-admin guard, org-admin cross-removal block, pending-invitation cancellation, idempotency, JWT revocation, email notification, and dual audit trail. See `TeamService.java:150-251` and RFC-0001.

### 3.9 JWT session management

- The auth-service mints **RS256** JWTs. The gateway validates them against the JWKS endpoint and injects trusted `X-CG-*` headers for the core server.
- **Revocation.** `TokenRevocationService` keys revocations by `(userId, orgId)`. Backed by Caffeine cache (capacity 10,000, `expireAfterWrite = ttlHours`) plus the `revoked_tokens` table. Warmed on `@PostConstruct`. Checked at `JwtAuthenticationFilter.java:119-127`; revoked non-PA sessions receive 401 immediately. Nightly cleanup at 03:00. Code: `server/src/main/java/com/certguard/service/TokenRevocationService.java`.
- PA sessions are intentionally exempt from revocation checks.

### 3.10 Platform-admin tools

- **Act-as-org impersonation.** `X-Acting-As-Org` + `X-Acting-As-Reason` header contract enforced in `JwtAuthenticationFilter.java:131-175`. See ADR-0007.
- **Org admin panel.** `/api/v1/org/admin/orgs/**` and `/api/v1/admin/orgs/{orgId}/...` endpoints, all `@PreAuthorize` PLATFORM_ADMIN-only: flat list, tree, detail, archive, restore, quota update.
- **Audit feed.** Paginated PA audit at `GET /api/v1/admin/audit` with filters on `actingUserId`, `targetOrgId`, and time range.

### 3.11 Org audit trail

`OrgAuditService.recordAsync` writes rows to `org_audit` from inside domain services after commit (e.g. `TeamService.java:237-238` for `MEMBER_REMOVED`). Visible to the affected tenant's ADMIN.

### 3.12 Platform-admin audit trail

`PlatformAdminAuditService.recordAsync` writes rows to `platform_admin_audit` from inside `JwtAuthenticationFilter` on every request that carried `X-Acting-As-Org`, capturing the response status even on 5xx. Domain services may additionally call it for sensitive write actions initiated by a PA. Paginated, filterable, PA-only.

### 3.13 Subscription enforcement

`SubscriptionGuard` raises `SubscriptionSuspendedException` → 403 with typed problem URI `https://certguard.dev/problems/subscription-suspended` when the billing-owner subscription status is `SUSPENDED`, blocking scan dispatch. `GlobalExceptionHandler.java:53-59`.

### 3.14 Internal Sales API

`/api/internal/v1/sales/**` is gated by `SalesAuthFilter` which validates `X-Sales-Key-Id` + `X-Sales-Key` (BCrypt-verified against `sales_api_keys` table, updates `last_used_at`). Not exposed publicly. Used by the SaaS operator's billing/CRM tooling.

## 4. API Surface

| Family | Path prefix | Auth | Roles |
|---|---|---|---|
| OAuth login / callback | `/oauth2/authorization/{google,microsoft}`, `/login/oauth2/code/*` | OAuth filter (auth-service) | public |
| Auth config / logout / invite | `/api/v1/auth/*` | public (some dev-mode-only) | — |
| Dashboard | `/api/v1/dashboard` | JWT | any authenticated |
| Certificates | `/api/v1/certificates*` | JWT | any authenticated |
| Targets | `/api/v1/targets*` | JWT | ADMIN/ENGINEER for writes |
| Locations | `/api/v1/locations*` | JWT | ADMIN/ENGINEER for writes |
| Org profile | `/api/v1/org*` | JWT | ADMIN for writes |
| Org members & invitations | `/api/v1/org/members*`, `/api/v1/org/invitations*` | JWT | ADMIN or PLATFORM_ADMIN |
| MSP clients | `/api/v1/msp/clients*` | JWT | ADMIN (MSP parent) or PLATFORM_ADMIN |
| Agent provisioning (bundle) | `POST /api/v1/agents`, `GET /api/v1/agents/{id}/bundle` | JWT (POST) / dlToken (GET) | ADMIN/ENGINEER/PLATFORM_ADMIN |
| Agent legacy admin/control | `/api/v1/agent/*` | JWT (mgmt) / public token+X-Org-Id (register) / agent headers (heartbeat/jobs/results) | varies |
| Admin org tools | `/api/v1/org/admin/orgs/**` | JWT | PLATFORM_ADMIN |
| Admin platform tools | `/api/v1/admin/**` | JWT | PLATFORM_ADMIN |
| Internal sales | `/api/internal/v1/sales/**` | SalesAuthFilter | sales key |
| Agent JAR download | `/agent/download`, `/agent/version` | public | — |
| Actuator | `/actuator/health,info,prometheus` | partial public | — |

## 5. Security Model

### 5.1 User auth — gateway header-trust pattern

The gateway is the only entity authorized to mint `X-CG-*` headers. It MUST
unconditionally strip any client-supplied `X-CG-*` headers before validation and
injection (HLD §1 security note). The core server's port 8443 is bound to `127.0.0.1`
in compose so direct access bypassing the gateway is not possible from outside the
Docker network.

### 5.2 Agent auth

Bearer-style `X-Agent-Id` (UUID) + `X-Agent-Key` (plaintext `"AGK-..."`). Server
BCrypt-verifies against `agents.agent_key_hash` and rejects unless `status = ACTIVE`.
Every scan result body carries an HMAC-SHA256 verified with constant-time compare.

### 5.3 Bundle issuance security

- Install key: 25 random bytes (~125 bits), base32-encoded with `"CGK-"` prefix. Never stored in plaintext.
- Bundle download token: 32 random bytes, base64url. SHA-256-hashed at rest. Single-use and time-limited.
- Sealed payload: AES-256-GCM (12-byte IV, 128-bit tag). Key is Argon2id-derived from the install key + 16-byte random salt (64 MiB memory, 3 iterations, parallelism 1).

### 5.4 CORS

Allow-list driven; startup refuses `allowedOriginPatterns=*` in non-dev mode (`SecurityConfig.java:121-127`).

### 5.5 Dev-mode safeguards

`APP_DEV_MODE` defaults to `false`. When `true`: disables OAuth, enables `/api/v1/auth/dev-token`, short-circuits SMTP sends, and relaxes CORS. Must remain `false` in production.

### 5.6 Token revocation

See §3.9 and RFC-0001. PA sessions are intentionally exempt from revocation enforcement.

## 6. Non-Functional Requirements (as implemented)

- **Distributed scheduling — ShedLock.** All scheduled tasks acquire a ShedLock before running so only one instance executes at a time across a multi-replica cluster. Wired via `SchedulerLockConfig.java`; `shedlock` table created by `V18__shedlock.sql`.
- **Virtual threads (Java 25 LTS).** Runtime baseline is Java 25 LTS.
- **Flyway migrations.** Schema owned by SQL migrations in `server/src/main/resources/db/migration/V*__*.sql`. Hibernate runs `ddl-auto: validate`. Flyway runs with `validateOnMigrate=true` and no `repair()`. (`FlywayConfig.java:12-20`).
- **RFC 9457 ProblemDetail errors.** Every controller exception is mapped to a `ProblemDetail` body by `GlobalExceptionHandler`. Typed problem URIs for machine-readable errors (e.g. `SubscriptionSuspendedException`).
- **UUID primary keys.** All JPA entities use `GenerationType.UUID` via a common `BaseEntity`.
- **HMAC scan result integrity.** HMAC-SHA256 over `targetId:scanType:serialNumber:notAfterMs`. SANs must be sorted before hashing in any extension of the scheme.
- **N+1 prevention.** `CertificateRecordRepository.findExpiringWithTargets` (JOIN FETCH) for the expiry sweep; `findLatestByTargetIds` (batch) for the target list. Enforced via code review on new queries.

## 7. What is intentionally not in this PRD

- Anything in `docs/architecture/GAPS.md` flagged as "not yet implemented."
- RabbitMQ — provisioned in compose but not on the hot path. Treat as infra, not a product capability.
- Refresh tokens — current auth flow does not issue them; access tokens are short-lived enough that re-login is acceptable.
- Per-JTI revocation — out of scope; see RFC-0001 §4.
