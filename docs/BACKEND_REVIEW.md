# CertGuard Server — Backend Review
**Source:** `certguard-server-source-20260419-0737/`
**Review date:** 2026-04-19
**Reviewer:** CertGuard Backend Engineer (read-only pass)

---

## 1. Layering Compliance (Controller → Service → Repository)

**Generally compliant.** All controllers delegate to services; no repository is injected directly into a controller.

**Violations / concerns:**

| Location | Issue |
|---|---|
| `TargetService.triggerScan` (line 151) | `SslScannerService` and `AgentService` are passed as method parameters from the controller, making the service aware of sibling service injection. This is an inversion of the expected pattern — the controller is passing spring beans down into the service as arguments. The service should inject those dependencies via constructor. |
| `DevAuthController` (lines 64–73) | Controller directly calls `OrganizationRepository`, `SubscriptionRepository`, and `UserRepository` — a three-layer violation. DB writes belong in a service. |
| `CertificateService` (no `@Transactional`) | Class-level `@Transactional(readOnly = true)` is completely absent. All public read methods (`listCertificates`, `getDashboard`, `getExpiring`) run outside any transaction boundary. |
| `OrgService.getOrg` / `listAllOrgs` | Both methods are missing `@Transactional(readOnly = true)`. They hit the DB but are not declared in any transaction. |
| `TeamService.listMembers` | Missing `@Transactional(readOnly = true)`. |
| `MspClientService.listClients` / `getClient` | Missing `@Transactional(readOnly = true)`. |
| `LocationService.listLocations` / `getLocation` | Missing `@Transactional(readOnly = true)`. |

---

## 2. Transaction Boundaries

**Pattern:** Convention requires class-level `@Transactional(readOnly = true)` with write methods overriding to `readOnly = false`.

**Findings:**

- `CertificateService` — no `@Transactional` annotation at all (class or method level). Any lazy-loaded association access during response mapping will throw a `LazyInitializationException` outside a session.
- `OrgService` — no class-level `@Transactional`; write methods individually annotated but reads are bare.
- `TeamService` — no class-level `@Transactional`; `listMembers` is an unguarded read.
- `MspClientService` — same as above; `listClients`/`getClient` are unguarded reads.
- `LocationService` — same; `listLocations`/`getLocation` unguarded.
- `AgentService` — `@Scheduled` method `scheduledPublicScan` in `SslScannerService` calls `targetRepository.findAllByIsPrivateFalseAndEnabledTrue()` with no transaction annotation; `CertificateExpiryScheduler.checkExpiringCertificates` is annotated `@Transactional(readOnly = true)` but then accesses `cert.getTarget()` (lazy) in a loop — this works only because it is still in-session, but is fragile if lazy loading configuration changes.
- `InvitationService.otpStore` is an in-memory `ConcurrentHashMap`. If the application runs in more than one JVM instance (horizontal scale), OTPs will be lost across nodes. Documented as a known limitation; must be flagged for production readiness.

---

## 3. JPA Entities

**UUID PKs / timestamps:** `BaseEntity` correctly uses `GenerationType.UUID` and Hibernate `@CreationTimestamp` / `@UpdateTimestamp`. All entities extend it. Compliant.

**FK correctness:**

- `CertificateRecord.orgId` is a plain `UUID` column (denormalised), not a `@ManyToOne`. This is intentional per the architecture (denormalisation for query performance) and the SQL migration matches. Acceptable, but there is no FK constraint on `certificate_records.org_id` referencing `organizations.id` in `V1__core_schema.sql` — a data integrity gap.
- `AgentRegistrationToken.createdBy` (V3 SQL, line 39) is a nullable `UUID REFERENCES users(id)` but the entity field (`createdBy`) is typed as `UUID` — not a `@ManyToOne`. The entity does not match the SQL FK; Hibernate will not enforce RI, and `ddl-auto: validate` will not catch the mismatch because the column type is still `UUID`.

**N+1 risks:**

- `TargetService.toResponse` (line 260): for every `Target` in a page, it issues a separate `certRepository.findAllByTargetId(target.getId())` call. A page of 20 targets generates 20 + 1 queries. This is the most serious N+1 in the codebase.
- `OrgService.listAllOrgs` (line 72): for every `Organization` returned by `findAll()`, it issues a separate `subscriptionRepository.findByOrganizationId(org.getId())` call.
- `MspClientService.listClients` (line 35): same pattern — per-org subscription query in a loop.
- `CertificateExpiryScheduler.checkExpiringCertificates`: iterates all org IDs and issues two queries per org, then accesses `cert.getTarget()` inside the loop (lazy, will trigger per-cert query without a JOIN FETCH).
- `TeamService.listMembers` / `toResponse`: accesses `m.getUser()`, `m.getInvitedBy()` on each member — potential N+1 on lazy-loaded users if `OrgMember` is fetched without JOIN FETCH.

---

## 4. REST API

**Versioning:** All functional endpoints are correctly under `/api/v1/`. No violations.

**ProblemDetail:** `GlobalExceptionHandler` maps all expected exception types to `ProblemDetail`. Compliant.

**Gaps in ProblemDetail:**
- `DevAuthController` (lines 121–123, 131) returns `ResponseEntity.badRequest().body(Map.of("error", ...))` — raw map, not `ProblemDetail`. Inconsistent error contract.
- `AgentController.register` (line 123) returns `ResponseEntity.badRequest().build()` with no body for a malformed `X-Org-Id` header.

**RBAC annotations:**
- `LocationController` — all five endpoints (GET list, GET by id, POST, PUT, DELETE) have **no** `@PreAuthorize`. Any authenticated user of any role can create/delete locations.
- `TargetController` — no `@PreAuthorize` on any endpoint; VIEWER-role users can create and delete targets.
- `CertificateController` — no `@PreAuthorize`; dashboard and certificate list are open to any authenticated user (acceptable, but VIEWER restriction is not enforced).
- `AgentController.generateToken` (`POST /tokens`) — no `@PreAuthorize`; any authenticated member can create agent registration tokens.
- `OrgController.updateProfile` / `updateName` — no `@PreAuthorize`; VIEWER can mutate the org profile.

**Input validation:**
- `TargetController.updateNotifications` (`PUT /{id}/notifications`) — `@RequestBody Map<String, Object>` has no `@Valid` and no size/content constraints. Arbitrary JSON is accepted and stored in JSONB.
- `AgentController.downloadConfig` — `agentName` query parameter has no length or character validation; it is embedded into file content returned to the caller (content injection risk).
- `OrgController.updateName` — `name` query parameter has no `@NotBlank` or length validation.

---

## 5. Security

**OAuth / JWT:**
- `app.dev-mode` defaults to `true` in `application.yml` (line 63). In dev mode, `oauth2Login` is **disabled** and the `/api/v1/auth/dev-token` endpoint is publicly accessible with no guard beyond the `devMode` flag. If this flag is ever misconfigured in production, any caller can mint an arbitrary JWT with any role including `PLATFORM_ADMIN`.
- JWT secret default value `local-dev-secret-key-change-this-in-production-must-be-at-least-64-chars!!` is committed in plain text in `application.yml` (line 65). While environment variable substitution is in place, the fallback default is a known, committed secret.
- SSL keystore default password `certguard123` is committed in `application.yml` (line 124). Same concern.
- `JwtTokenProvider` does not verify `iss` (issuer) or `aud` (audience) claims. Any token signed with the same key (e.g., from another environment using the default secret) would be accepted.

**CSRF:** Disabled globally (`csrf.disable()`). Acceptable for a stateless JWT API, but documented here for awareness.

**CORS:** `allowedOriginPatterns(List.of("*"))` with `allowCredentials(true)` (`SecurityConfig` lines 91–94). This is a misconfiguration — browsers block `allowCredentials=true` with a wildcard origin pattern at the spec level, but the intent to allow all origins defeats CORS protection. Production must restrict this to known frontend origins.

**`devMode` gate-keeping:**
- `DevAuthController.devToken` checks `devMode` at runtime (line 53), but the endpoint is publicly exposed in the security filter chain (line 65 of `SecurityConfig`) regardless of `devMode`. There is no build-time or deployment-time removal. A `devMode=false` misconfiguration is the only production protection.

---

## 6. DB Migrations vs `ddl-auto`

`spring.jpa.hibernate.ddl-auto: none` (application.yml line 21) — correct, no auto-DDL.
`spring.flyway.enabled: false` (application.yml line 30) — **Flyway is disabled**. Migrations in `db/migration/` (V1–V7) exist but are never run automatically by the application. There is a separate `FlywayConfig.java` class but the global `flyway.enabled=false` overrides it. Schema drift between the SQL files and the live DB is entirely possible and will not be caught at startup. This is a critical operational risk.

**Schema drift identified:**

| Migration | Entity | Mismatch |
|---|---|---|
| V1 `certificate_records` has no FK on `org_id` | `CertificateRecord.orgId` | Intentional denorm but no DB constraint — orphaned cert records possible if org deleted |
| V3 `agent_registration_tokens.created_by` is a FK `UUID` column | `AgentRegistrationToken` entity has `createdBy` typed as `UUID` (bare column), no `@ManyToOne` | Entity and SQL agree on column type but the entity bypasses FK enforcement |
| `invitations` table (V5) has no `updated_at` column | `Invitation` extends `BaseEntity` which maps `updated_at` | V7 `fix_invitations_updated_at.sql` presumably adds it — review V7 |

**V7 fix:** The existence of `V7__fix_invitations_updated_at.sql` confirms that a previous migration (`V5`) shipped without `updated_at` on `invitations`, causing `ddl-auto: validate` to fail had it been enabled. This is exactly the kind of drift that a disabled Flyway allows to go undetected.

---

## 7. Multi-Tenant Org-Scoping

**Mechanism:** `TenantContext` (ThreadLocal) is populated by `JwtAuthenticationFilter` from the JWT `orgId` claim. Services use `TenantContext.getOrgId()` passed down from controllers.

**Compliant paths:** `TargetService`, `CertificateService`, `LocationService`, `AgentService`, `TeamService`, `MspClientService` all scope queries by `orgId`.

**Gaps:**

- `CertificateExpiryScheduler.checkExpiringCertificates` (line 51): calls `organisationRepository.findAll()` — a cross-tenant query that returns all orgs. This is by design for the scheduler, but it then accesses `cert.getTarget().getNotificationChannels()` across all orgs with no isolation guard. If `notificationService` has a bug, it could dispatch alerts across tenant boundaries.
- `SslScannerService.scheduledPublicScan` (line 46): calls `targetRepository.findAllByIsPrivateFalseAndEnabledTrue()` — cross-tenant by design for the public scan. Acceptable but must be audited if per-org scan scheduling is ever introduced.
- `OrgController.listAllOrgs` (admin, line 51) is gated by `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`. Correct.
- `DevAuthController` creates organizations and users directly in a controller without org-scoping checks. This is a dev-only path but the lack of a service layer means no quota or business rule enforcement.

---

## 8. Prioritised Fix List

### P0 — Critical (data loss, security breach, or system failure in production)

| ID | File:Line | Issue |
|---|---|---|
| P0-1 | `application.yml:30` | `spring.flyway.enabled: false` — Flyway is disabled; migrations never run automatically. Schema drift goes undetected at startup. Must be enabled (or an alternative migration runner configured). |
| P0-2 | `application.yml:63` | `app.dev-mode: true` default combined with the publicly accessible `POST /api/v1/auth/dev-token` creates an unauthenticated privilege-escalation path if deployed without the env var set. Default must be `false`. |
| P0-3 | `application.yml:65` | JWT secret has a committed plaintext fallback. Default must be empty or invalid so the application refuses to start without a real secret. |
| P0-4 | `SecurityConfig.java:91–94` | `allowedOriginPatterns("*")` + `allowCredentials(true)` is a CORS misconfiguration. Restrict `allowedOriginPatterns` to known frontend origins in production. |
| P0-5 | `DevAuthController.java` (entire class) | Controller directly writes to three repositories — a layer violation that bypasses business rules, quotas, and transactional integrity. Move to a `DevAuthService`. |

### P1 — High (correctness, data integrity, or significant performance degradation)

| ID | File:Line | Issue |
|---|---|---|
| P1-1 | `TargetService.java:260` | N+1 in `toResponse`: `certRepository.findAllByTargetId` called per target in a page. Replace with a single JOIN FETCH or `IN` query with a batch by target IDs. |
| P1-2 | `CertificateService.java:23` | Class has no `@Transactional` annotation. All reads are outside a session; lazy-loaded associations on `CertificateRecord.target` in `toResponse` will throw `LazyInitializationException`. Add class-level `@Transactional(readOnly = true)`. |
| P1-3 | `LocationController.java` (all endpoints) | No `@PreAuthorize` on any endpoint. Authenticated VIEWER-role users can create and delete locations. Apply at minimum `@PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")` on write endpoints. |
| P1-4 | `TargetController.java` (POST, PUT, DELETE) | No `@PreAuthorize` — VIEWER can create/delete targets. Same fix as P1-3. |
| P1-5 | `AgentController.java:39` (`POST /tokens`) | No `@PreAuthorize` — any authenticated member can create registration tokens. Add `hasAnyRole('ADMIN','PLATFORM_ADMIN')`. |
| P1-6 | `OrgController.java:37,43` | `PUT /profile` and `PUT /name` have no RBAC guard. Add `@PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")`. |
| P1-7 | `V1__core_schema.sql:84` | `certificate_records.org_id` has no FK constraint to `organizations`. If an org is deleted (CASCADE on `targets`) the cert records for that org remain with an orphaned `org_id`. Add the FK or rely on the `target_id` CASCADE to clean up. |
| P1-8 | `OrgService.java:25,71` / `MspClientService.java:33,41` / `LocationService.java:26,31` / `TeamService.java:42` | Read methods missing `@Transactional(readOnly = true)` — can cause `LazyInitializationException` and do not participate in connection pool optimisation. |

### P2 — Medium (code quality, operational risk, or minor inconsistency)

| ID | File:Line | Issue |
|---|---|---|
| P2-1 | `TargetService.java:151` | `SslScannerService` and `AgentService` passed as method parameters. Inject via constructor instead. |
| P2-2 | `InvitationService.java:55` | In-memory OTP store fails under horizontal scaling. Add a Redis-backed store (or at minimum document the single-instance constraint as a deployment prerequisite). |
| P2-3 | `OrgService.java:72` / `MspClientService.java:33` | N+1 subscription query per org in list methods. Use a `JOIN FETCH` or batch fetch. |
| P2-4 | `DevAuthController.java:121,131` / `AgentController.java:123` | Non-ProblemDetail error responses (`Map.of("error", ...)`) break the RFC 9457 contract. Use `ProblemDetail` or throw typed exceptions caught by `GlobalExceptionHandler`. |
| P2-5 | `TargetController.java:75` | `PUT /{id}/notifications` accepts unconstrained `Map<String, Object>` without `@Valid` or size limits. Define a typed request DTO with validation. |
| P2-6 | `AgentController.java:56` | `agentName` query param embedded in generated config file content with no sanitisation. Add `@Pattern` / length constraint. |
| P2-7 | `application.yml:124` | SSL keystore default password `certguard123` committed. Empty the default so the app fails fast if env var is not set. |
| P2-8 | `JwtTokenProvider.java` | Token validation does not check `iss` or `aud`. Add issuer/audience claims and verification to prevent cross-environment token reuse. |
| P2-9 | `CertificateExpiryScheduler.java:80–88` | The "already expired" query re-fetches certs via a second `findExpiringByOrgId` call per org with a 365-day lookback. Alerts are sent for every expired cert on every daily run — no deduplication or back-off. This will generate alert storms for long-expired certificates. |
