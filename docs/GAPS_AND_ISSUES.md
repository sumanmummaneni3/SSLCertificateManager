# CertGuard — Gaps and Issues

> Analysis performed: 2026-05-02  
> Baseline tag: `v1.0-baseline`  
> Status legend: ✅ Fixed | ⚠️ Partial | ❌ Open

---

## 1. Schema / Database Issues

| # | Issue | Status | Reference |
|---|---|---|---|
| S1 | `targets` table had no unique constraint on `(org_id, host, port)` — duplicate target rows possible under concurrent inserts | ✅ Fixed | `V12__schema_integrity_constraints.sql` |
| S2 | `org_notification_channels` had no uniqueness on `(org_id, channel_type)` — two ADMINs could create duplicate rows, causing double-sends | ✅ Fixed | `V12__schema_integrity_constraints.sql` |
| S3 | `certificate_records.org_id` denormalised without FK to `organizations` — orphan rows possible on org delete | ❌ Open | `V1__core_schema.sql:84`; `BACKEND_REVIEW.md` P1-7 |
| S4 | `agent_scan_jobs.org_id` denormalised without FK — same orphan risk | ❌ Open | `V3__agent_schema.sql:49` |
| S5 | Two coexisting role enums: `user_role(ADMIN,MEMBER,VIEWER,PLATFORM_ADMIN)` vs `org_member_role(ADMIN,ENGINEER,VIEWER)` — drift risk between `User.role` (legacy) and `OrgMember.role` (current RBAC) | ⚠️ Partial | `LLD.md` §3; `JwtTokenProvider.java:34` still reads `user_role` |
| S6 | `users.email` globally UNIQUE conflicts with MSP model where one person can belong to multiple orgs | ❌ Open | `V1__core_schema.sql:33`; `LLD.md` ER diagram |
| S7 | `agent_registration_tokens` BCrypt hash with no prefix lookup column — registration is O(N) BCrypt checks over all tokens in the org | ✅ Fixed | `AgentService.java` token cleanup; `2c25939` |
| S8 | `certificate_records(target_id, serial_number)` had no unique index — concurrent FULL scans could insert duplicate cert rows | ✅ Fixed | `V12__schema_integrity_constraints.sql` |
| S9 | `targets.last_error_message` / `last_error_at` columns missing — scan failures silently swallowed with no stored reason | ✅ Fixed | `V15__target_last_error.sql` |
| S10 | No composite index on `certificate_records(org_id, expiry_date)` for expiry scheduler queries | ✅ Fixed | `V17__cert_records_composite_index.sql` |
| S11 | `agent_scan_jobs` claim has no `SELECT … FOR UPDATE SKIP LOCKED` — two agents sharing an ID can double-claim | ✅ Fixed | `AgentScanJobRepository.claimPendingJobsWithLock`; `AgentService.pollJobs` |
| S12 | V11 semantic gap: no DB constraint enforces that `agent_registration_tokens.agent_id` points to a PENDING agent | ❌ Open | `V11__link_reg_token_to_agent.sql` |

---

## 2. Backend Service Gaps

### 2.1 Auth / Security

| Issue | Status | Reference |
|---|---|---|
| mTLS symbolic — `AgentCertificateAuthority` generates keypair but discards private key; agent gets cert with no key material; server never enforces `clientAuth=need` | ❌ Open | `AgentCertificateAuthority.java:122–151`; `SecureHttpClient.java:79–107` |
| JWT lacked `iss`, `aud`, `jti` claims; 24 h TTL; no revocation | ✅ Fixed | `JwtTokenProvider.java`; commit `046d97d` |
| CORS `allowedOriginPatterns=*` with `allowCredentials=true` | ❌ Open | `SecurityConfig.java:90–94`; `BACKEND_REVIEW.md` P0-4 |
| `app.dev-mode` defaults enabled unauthenticated `POST /api/v1/auth/dev-token` | ❌ Open | `application.yml:63`; `DevAuthController.java:48–86` |
| JWT secret and keystore password have committed plaintext fallback values | ❌ Open | `application.yml:65,124` |
| `AgentAuthFilter` 401 returned raw `{"error":"…"}` instead of RFC 9457 ProblemDetail | ✅ Fixed | `AgentAuthFilter.java:112–117`; commit `11dd7d8` |

### 2.2 Organizations / MSP

| Issue | Status | Reference |
|---|---|---|
| MSP drill-in endpoints missing — no `/msp/clients/{id}/targets`, `/msp/clients/{id}/agents` | ❌ Open | Only `/api/v1/msp/clients` CRUD exists |
| `updateQuota` accepted unbounded `int` — negative or zero would reject all future targets | ✅ Fixed | `OrgController.java`; `@Min(1)` added; commit `11dd7d8` |
| `OrgService.listAllOrgs` N+1 subscription query | ❌ Open | `BACKEND_REVIEW.md` P2-3 |

### 2.3 Targets / Certificates

| Issue | Status | Reference |
|---|---|---|
| No `GET /api/v1/certificates/{id}` detail endpoint | ❌ Open | `CertificateController` has list/expiring/dashboard only |
| No PEM download endpoint — `public_cert_b64` stored but never exposed | ❌ Open | `CertificateRecord.java:publicCertB64` |
| No certificate history endpoint (per-target series) | ❌ Open | Repo method `findAllByTargetId` exists but no route |
| No bulk import for targets (CSV/JSON) | ❌ Open | Only single-target POST |
| `targets.tags` JSONB in LLD ERD but absent from schema and entity | ❌ Open | `LLD.md:148`; `Target.java` |
| `Target.enabled` flag has no UI control to disable | ❌ Open | UI gap — badge shown, no toggle |
| N+1 in expiry scheduler (per-org cert queries, lazy target access) | ✅ Fixed | `CertificateExpiryScheduler.java`; single JOIN FETCH query; commit `761e464` |
| Scan errors swallowed with no stored message | ✅ Fixed | `V15__target_last_error.sql`; `SslScannerService.java`; commit `761e464` |
| N+1 in `TargetService.toResponse` (per-target cert lookup in a loop) | ✅ Fixed | `TargetService.listTargets` batch-loads via `findLatestByTargetIds`; `CertificateRecordRepository` |
| `PUT /api/v1/targets/{id}/notifications` accepted unconstrained `Map<String,Object>` with no size limit | ✅ Fixed | `TargetService.updateNotificationChannels`; commit `11dd7d8` |

### 2.4 Agents

| Issue | Status | Reference |
|---|---|---|
| `queueScanJob(Target)` overload bypassed org-scope check | ✅ Fixed | `AgentService.java:251–253`; delegated to org-scoped overload; commit `11dd7d8` |
| `agents.current_target_count` drifts — not decremented on cascade delete | ❌ Open | `TargetService` create/delete; no reconciliation job |
| No agent re-activation endpoint (REVOKED → ACTIVE requires manual SQL) | ❌ Open | `AgentController` only has `/revoke` |
| No agent log streaming back to server | ❌ Open | Not in HLD |
| No agent self-update | ❌ Open | `/agent/version` endpoint exists; no poll-and-swap in agent |
| Agent `locationId` accepted by server but silently discarded | ✅ Fixed | `V14__agent_location.sql`; `Agent.java`; `AgentBundleService.java`; commit `046d97d` |
| `cleanupExpiredTokens` deleted tokens linked to PENDING agents, orphaning them | ✅ Fixed | `AgentService.java:319–326`; fetch-filter-delete; commit `2c25939` |
| `AgentOfflineScheduler` emailed on every 5-minute tick after threshold — no dedup | ✅ Fixed | `V13__agent_offline_alert_dedup.sql`; `AgentOfflineScheduler.java`; commit `11dd7d8` |
| Agent job-claim race — no `FOR UPDATE SKIP LOCKED` | ❌ Open | `AgentService.java:147–154` |

### 2.5 Notifications

| Issue | Status | Reference |
|---|---|---|
| SMS / Slack / Teams / webhook channels are model stubs — log `[COMING SOON]` | ❌ Open | `NotificationService.java:213–222` |
| No "test send" endpoint to verify channel configuration | ❌ Open | Not designed |
| No notification channel editor in UI | ❌ Open | See §4 |
| No expiry alert dedup for already-expired certs (alert storms) | ❌ Open | `CertificateExpiryScheduler.java:80–88` |

### 2.6 Jobs / Schedulers

| Issue | Status | Reference |
|---|---|---|
| FAILED jobs never retried — no backoff state machine | ❌ Open | `AgentScanJob` status enum has no RETRYING |
| No leader election — every server replica runs all `@Scheduled` methods (duplicate work, double notifications) | ✅ Fixed | ShedLock 6.9.2 via JDBC; `SchedulerLockConfig`, `V18__shedlock.sql`; `@SchedulerLock` on all 6 scheduled methods |
| No DB lock on job claim (see S11) | ✅ Fixed | `AgentScanJobRepository.claimPendingJobsWithLock` with `FOR UPDATE SKIP LOCKED` |

---

## 3. Agent-Side Issues

| Issue | Status | Reference |
|---|---|---|
| mTLS private key never delivered to agent | ❌ Open | `AgentCertificateAuthority.java:122–151` |
| Hostname verification disabled — `NoopHostnameVerifier` called 4× (copy-paste) | ✅ Fixed | `SecureHttpClient.java:54–57`; commit `2c25939` |
| `AgentConfig.set` wrote config non-atomically — crash mid-write corrupts file | ✅ Fixed | `AgentConfig.java:191–196`; temp+`ATOMIC_MOVE`; commit `2c25939` |
| HMAC scope covered only 4 fields — full FULL-scan body was unsigned | ✅ Fixed | `HmacSigner.java`; `AgentHmacService.java`; commit `2c25939` |
| Agent key stored plaintext in `application.properties` on disk | ❌ Open | `AgentConfig.java:81–95` |
| No retry/backoff on server outage — each tick logs and continues | ❌ Open | `PollLoop.java:63–103` |
| No structured exit on 401 (revoked agent) — agent loops forever | ❌ Open | `PollLoop`, `ServerApiClient` |
| In-memory serial cache (`serialCache`) lost on restart — always FULL scan after restart | ❌ Open | `SslScanner.java:42` |
| No CIDR enforcement on agent itself — relies solely on server-side validation | ❌ Open | `AgentConfig.allowedCidrs` not checked pre-scan |
| `Files.setPosixFilePermissions` silently skipped on non-POSIX (Windows) | ❌ Open | `RegistrationService.java:56–61` |

---

## 4. UI / Frontend Gaps

| Issue | Status | Reference |
|---|---|---|
| No cert detail view — clicking a cert row does nothing | ❌ Open | `certguard-ui.jsx`; no detail route |
| Expiring-soon report — `GET /certificates/expiring` API exists but UI never calls it | ❌ Open | `api.getExpiring` defined but unused |
| No notification channel editor in `EditTargetModal` | ❌ Open | `TargetController.java:72–77` has endpoint; no UI widget |
| No platform-admin console UI | ❌ Open | `/api/v1/org/admin/*` endpoints exist server-side |
| `MspOrgsView` is a placeholder stub | ❌ Open | `certguard-ui.jsx:2857–2876` |
| `AgentCreateWizard` collected `locationId` but server discarded it | ✅ Fixed | Commit `046d97d`; location now wired end-to-end |
| Scan progress opaque — UI uses 10 s sleep instead of polling `/scan-status` | ❌ Open | `certguard-ui.jsx:1519` |
| No search / filter on targets or certificates lists | ❌ Open | |
| No bulk operations or CSV export | ❌ Open | |
| Single monolithic 3000-line `certguard-ui.jsx` — no router, no code splitting | ❌ Open | `GAPS.md` D5 |
| `API_BASE = ""` hardcoded — cross-origin deploy requires code change | ❌ Open | `certguard-ui.jsx:6` |
| `Target.enabled` badge shown but no toggle to disable a target | ❌ Open | `certguard-ui.jsx:1853–1855` |

---

## 5. Competitive Feature Gaps

Compared against: Sectigo Certificate Manager, DigiCert CertCentral, Venafi (CyberArk), Keyfactor Command, GlobalSign Atlas, Smallstep, cert-manager, SSLMate Cert Spotter.

### 5.1 Certificate Issuance (largest gap — CertGuard is monitoring-only)
- ❌ ACME server (RFC 8555)
- ❌ SCEP / EST / CMP / NDES enrollment protocols
- ❌ Private CA for internal certificates
- ❌ Automated certificate renewal

### 5.2 Discovery
- ❌ Certificate Transparency (CT) log monitoring — shadow-IT / rogue cert detection
- ❌ Subdomain enumeration / crt.sh-style discovery
- ❌ Network CIDR sweep for open TLS ports
- ❌ Cloud connectors (AWS ACM, Azure Key Vault, GCP Certificate Manager)
- ❌ Kubernetes `Ingress` / TLS Secret discovery
- ❌ Load-balancer / WAF connectors (F5, Citrix ADC, NGINX, HAProxy)

### 5.3 Notifications & Integrations
- ❌ Slack, Microsoft Teams, PagerDuty, OpsGenie
- ❌ Generic outbound webhook
- ❌ SIEM forwarding (Splunk HEC, Elastic, syslog)
- ❌ PSA / ServiceDesk ticketing (ConnectWise, ServiceNow)

### 5.4 Compliance & Reporting
- ❌ PCI-DSS / HIPAA / SOC 2 report templates
- ❌ CSV / PDF / XLSX export
- ❌ Scheduled digest email reports
- ❌ Audit log of user actions and role changes

### 5.5 Cryptography / TLS Posture
- ❌ TLS handshake grading (SSL Labs A+/B/F-style)
- ❌ Protocol / cipher posture UI (TLS 1.0 still enabled, weak cipher warnings)
- ❌ OCSP / CRL / revocation status checks
- ❌ Post-quantum cryptography readiness assessment

### 5.6 Identity Beyond TLS
- ❌ SSH host-key / certificate inventory
- ❌ Code-signing certificate lifecycle
- ❌ Client cert / smart-card workflows

### 5.7 Operational
- ❌ HA / multi-region scheduling (leader election)
- ❌ SSO beyond Google (SAML, Okta, Azure AD, Auth0)
- ❌ MFA (TOTP / WebAuthn)
- ❌ SCIM 2.0 provisioning
- ❌ Tiered subscription enforcement beyond a single quota integer

---

## 6. Fix Changelog (post `v1.0-baseline`)

| Commit | Fixes applied |
|---|---|
| `761e464` | Expiry scheduler N+1 → single JOIN FETCH query; V15 scan error message persistence (`last_error_message`, `last_error_at`) on targets and TargetResponse |
| `046d97d` | JWT `iss`, `aud`, `jti` claims; TTL reduced 24 h → 8 h; V14 agent location FK wired end-to-end (entity, bundle service, response DTO, AgentRepository JOIN FETCH) |
| `2c25939` | `AgentConfig.set` atomic write (temp + `ATOMIC_MOVE`); `NoopHostnameVerifier` reduced to one call; HMAC widened to cover all FULL-scan fields; `cleanupExpiredTokens` skips tokens linked to PENDING agents; `findExpiredAndUsed` repo method added |
| `11dd7d8` | V12 unique constraints (`targets`, `cert_records`, `org_notification_channels`) + performance indexes; V13 `last_offline_alert_sent_at` for offline-alert dedup; `AgentAuthFilter` → RFC 9457 ProblemDetail; `AgentOfflineScheduler` 24 h dedup; `queueScanJob(Target)` delegates to org-scoped overload; `@Min(1)` on `updateQuota`; notification channels size limit |
| `58968e3` | Agent bundle provisioning (Argon2id + AES-256-GCM); two-pass TLS scanning (BC JSSE TLS 1.2 + JVM TLS 1.3 fallback); dev-mode default off; CORS guard; Flyway `validateOnMigrate=true` |
| `9ab5ea7` | Sign-out button in sidebar |
| (pending) | P1-2: `TargetService.listTargets` batch cert load via `findLatestByTargetIds` (eliminates N+1); P1-5: ShedLock 6.9.2 wired — `SchedulerLockConfig`, `V18__shedlock.sql`, `@SchedulerLock` on all 6 `@Scheduled` methods; P1-12/S10: `V17__cert_records_composite_index.sql` composite index on `(org_id, expiry_date)` |

---

## 7. Remaining Open Issues — Prioritised Backlog

### P0 — Security / Data-Integrity Blockers

| ID | Issue | File(s) | Suggested fix | Effort |
|---|---|---|---|---|
| P0-1 | `app.dev-mode` default enables unauthenticated JWT minting | `application.yml:63`, `DevAuthController.java`, `SecurityConfig.java` | Default `false`; gate `DevAuthController` behind `@Profile("dev")` | S |
| P0-2 | CORS wildcard with `allowCredentials=true` | `SecurityConfig.java:90–94` | Read allowlist from config; reject `*` at startup when credentials mode enabled | S |
| P0-3 | JWT secret and keystore password have committed plaintext fallbacks | `application.yml:65,124` | Blank defaults; fail-fast if `< 64 chars` in `JwtTokenProvider` | S |
| P0-4 | mTLS symbolic — decide and implement | `AgentCertificateAuthority.java`, `SecureHttpClient.java`, Tomcat connector | Either return private key to agent and enforce `clientAuth=need`, or remove CA path and document bearer-only as the design | L |
| P0-5 | Agent hostname verification disabled | `SecureHttpClient.java:54–57` | Use `DefaultHostnameVerifier` when fingerprint pin is configured | S |
| P0-6 | Agent key stored plaintext on disk | `AgentConfig.java:81–95` | Encrypt with OS keystore (DPAPI / Keychain / libsecret) or machine-bound KEK | M |
| P0-7 | Job-claim race — no `FOR UPDATE SKIP LOCKED` | `AgentService.java:147–154`, `AgentScanJobRepository` | Native query: `UPDATE … WHERE status='PENDING' … RETURNING *` with skip-locked | S |
| P0-8 | `DevAuthController` writes directly to repositories, bypassing quotas | `DevAuthController.java:64–73` | Move logic to service layer; add `@Profile("dev")` | S |

### P1 — Correctness Bugs

| ID | Issue | File(s) | Suggested fix | Effort |
|---|---|---|---|---|
| P1-1 | `agents.current_target_count` drifts on cascade delete | `TargetService` create/delete | Replace with `COUNT(*)` sub-query or nightly reconciler | M |
| P1-2 | N+1 in `TargetService.toResponse` (cert lookup per target in a loop) | `TargetService.java:296` | Batch `IN (target_ids)` query, group in memory | ✅ Fixed |
| P1-3 | Expiry alert dedup missing — already-expired certs cause alert storms | `CertificateExpiryScheduler.java:80–88` | Add `notifications_sent(cert_id, severity, day)` table or extend `last_alert_sent_at` check | M |
| P1-4 | No retry / backoff for FAILED scan jobs | `AgentService`, `AgentScanJob` | Add `attempt_count` + `next_attempt_at`; exponential backoff up to 5 attempts | M |
| P1-5 | No leader election for `@Scheduled` — duplicate runs in HA deploy | All schedulers | Adopt ShedLock with Postgres lock provider | ✅ Fixed |
| P1-6 | Agent has no retry/backoff on server outage | `PollLoop.java:63–103` | Exponential backoff up to `pollInterval × 2^N` (cap at e.g. 5 min) | S |
| P1-7 | Agent does not exit on persistent 401 (revoked) | `PollLoop`, `ServerApiClient` | After 3 consecutive 401s, write marker file and `System.exit(78)` | S |
| P1-8 | Agent serial cache lost on restart → always FULL scan | `SslScanner.java:42` | Persist `serial_cache.json` next to `application.properties` | S |
| P1-9 | `OTP store` is in-memory `ConcurrentHashMap` — lost across nodes | `InvitationService.java:59` | Move to `invitation_otp` DB table with TTL or Redis | M |
| P1-10 | Two role enums coexisting — `user_role` vs `org_member_role` | `JwtTokenProvider.java:34`, V1/V5 SQL | Deprecate `users.role`; migrate auth to `org_members.role` | M |
| P1-11 | `users.email` globally unique — MSP multi-org membership impossible | `V1__core_schema.sql:33` | Drop UK; rely on `(user_id, org_id)` in `org_members` | M |
| P1-12 | Missing index on `certificate_records(org_id, expiry_date)` | Schema | New migration adding composite index | ✅ Fixed |

### P2 — Production-Readiness Features

| ID | Feature | Suggested fix | Effort |
|---|---|---|---|
| P2-1 | Certificate detail endpoint | `GET /api/v1/certificates/{id}` — full record with PEM, SANs, chain | S |
| P2-2 | PEM download | `GET /api/v1/certificates/{id}/pem` returning `application/x-pem-file` | S |
| P2-3 | Certificate history per target | `GET /api/v1/targets/{id}/certificates` | S |
| P2-4 | Bulk target import | `POST /api/v1/targets:bulk` (CSV multipart or JSON array) | M |
| P2-5 | `targets.tags` JSONB unused | Wire CRUD + `?tag=` query filter | S |
| P2-6 | Notification test-send | `POST /api/v1/orgs/{orgId}/notifications:test` | S |
| P2-7 | Slack / Teams / webhook dispatchers | Implement real notification dispatchers | L |
| P2-8 | Notification channel editor in UI | Build `NotificationChannelsModal` component | M |
| P2-9 | Platform-admin console in UI | Build `/admin` route for org / quota / agent management | M |
| P2-10 | MSP drill-in UI | Build child-org tabs: targets, agents, certs | L |
| P2-11 | Scan progress polling in UI | Poll `/api/v1/targets/{id}/scan-status` every 2 s with backoff | S |
| P2-12 | Search / filter / pagination on targets and certs | Query-state hooks + server-side filter params | M |
| P2-13 | Bulk operations + CSV export | Multi-select UI bar; `?format=csv` on list endpoints | M |
| P2-14 | UI refactor | Split monolith: feature folders, React Router, `apiClient.js`, `VITE_API_BASE` env var | L |
| P2-15 | Audit log | `audit_events` table + `@Auditable` aspect; admin console view | L |
| P2-16 | `Target.enabled` toggle in UI | Add enable/disable button in `EditTargetModal` | S |
| P2-17 | Agent re-activation endpoint | `POST /api/v1/agent/{id}/activate` (REVOKED → ACTIVE) | S |
| P2-18 | CIDR enforcement on agent itself | Validate target host inside `allowed_cidrs` before scanning | S |

### P3 — Nice-to-Have / Competitive Parity

| ID | Feature | Effort |
|---|---|---|
| P3-1 | ACME issuer endpoint (RFC 8555) | XL |
| P3-2 | CT log monitoring | L |
| P3-3 | Cloud connectors (AWS ACM, Azure Key Vault, GCP) | XL |
| P3-4 | Kubernetes ingress/secret discovery | L |
| P3-5 | TLS handshake grading (SSL Labs-style) | L |
| P3-6 | OCSP / CRL freshness checks | M |
| P3-7 | Post-quantum readiness flag | M |
| P3-8 | SSH host-key inventory | L |
| P3-9 | SSO beyond Google (SAML, Okta, Azure AD) | M |
| P3-10 | MFA (TOTP / WebAuthn) | L |
| P3-11 | SCIM 2.0 provisioning | L |
| P3-12 | Agent self-update | M |
| P3-13 | Agent log streaming to server | L |
| P3-14 | PCI-DSS / SOC 2 report templates + PDF export | M |
| P3-15 | HA scheduling via ShedLock or Quartz cluster | M |

---

## Status Summary

| Category | ✅ Fixed | ⚠️ Partial | ❌ Open |
|---|---|---|---|
| Schema | 5 | 1 | 6 |
| Backend services | 10 | 1 | 18 |
| Agent | 4 | 0 | 6 |
| UI / Frontend | 1 | 0 | 11 |
| Competitive features | — | — | 28 |
| **Total** | **20** | **2** | **69** |

> P0 items (8 open) should be resolved before any production deployment.  
> P1 items (12 open) affect correctness and should be resolved within the first sprint after P0.  
> P2 / P3 items can be prioritised against product roadmap.
