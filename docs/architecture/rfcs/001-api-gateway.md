# RFC 0001 — API Gateway, Unified Identity, and PHP Portal Integration

- Status: Proposed
- Author: CertGuard Architect
- Date: 2026-05-15
- Related: `docs/architecture/HLD.md`, `docs/architecture/LLD.md`, `docs/architecture/GAPS.md`
- Stakeholders: backend-engineer, frontend-engineer, PHP integrator, platform ops

## 1. Summary

Introduce a dedicated **API Gateway** (Spring Cloud Gateway 4.x) in front of `certguard-server`, `certguard-auth-service`, the upcoming **PHP Portal** microservice, and future services (`CertManagerCloud`, `certmonitor`). Keep `certguard-auth-service` as the single Security Token Service (STS). All clients — React SPA, Java consumers, the PHP portal — authenticate against the auth service and present the issued JWT to the gateway. Roles (`ADMIN`, `ENGINEER`, `VIEWER`, `PLATFORM_ADMIN` aka "Platform Engineer") are enforced **coarsely at the gateway** and **fine-grained inside each service**.

## 2. Goals

1. One ingress, one origin, one CORS policy.
2. Language-agnostic auth (works for Java, JS, PHP) via JWT bearer tokens.
3. Single source of truth for identity (auth-service) — eliminate the duplicate token issuer in `certguard-server` (`AuthController` / `DevAuthController` / `JwtTokenProvider`).
4. Consistent role propagation across all services.
5. Make adding a new backend service a routing-config change, not a security change.
6. Zero functional regression for the React SPA or agents during rollout.

## 3. Non-Goals

- Replacing the agent's mTLS+HMAC channel — agents remain on a dedicated listener.
- Service mesh / sidecar adoption.
- Multi-region active-active (separate RFC).
- Replacing PostgreSQL for sessions.

## 4. Decision

### 4.1 Gateway product: Spring Cloud Gateway 4.x (reactive, Java 17, Spring Boot 4.0.x)

Rationale:
- Matches existing toolchain (no new language/runtime for ops).
- First-class JWT support via `spring-boot-starter-oauth2-resource-server` filter on the gateway's `SecurityFilterChain`.
- Programmatic + YAML route DSL.
- Native Resilience4j and Micrometer integration consistent with existing services.
- Out-of-process — failure of an upstream service does not crash the gateway.

Rejected alternatives:
- **NGINX/OpenResty** — viable but requires Lua for JWT introspection; weaker dev ergonomics.
- **Kong/APISIX** — heavier ops surface, additional control plane (Postgres for Kong, etcd for APISIX); overkill for current footprint.
- **Auth-service-as-gateway** — see §10 "Rejected alternatives".

### 4.2 Token strategy

**Phase 1 (now):** Keep HS256, single shared secret stored in `auth.jwt.secret`. Gateway, certguard-server, and any future Java services hold the same secret and validate locally. The PHP portal runs **inside the trust zone** and may also hold the secret OR use `POST /api/auth/validate`. PHP MUST NOT receive the HS256 secret if it runs outside the trust zone.

**Phase 2 (target, ≤ 6 months):** Migrate to **RS256** with a `KeyPair` rotated quarterly. Auth-service publishes `GET /.well-known/jwks.json`. All services validate via JWKS. PHP uses `web-token/jwt-framework` to validate offline. Revocation continues to work via `jti` lookup against `auth_user_sessions`.

### 4.3 Unified JWT claim contract

Every JWT issued after Phase 1 cutover MUST contain (additions to today's contract in **bold**):

| Claim | Type | Required | Notes |
|---|---|---|---|
| `iss` | string | yes | `certguard-auth` (single issuer post-Phase 3) |
| `aud` | string[] | yes | `["certguard-apps"]` |
| `sub` | UUID | yes | auth_users.id |
| `email` | string | yes | |
| `provider` | string | yes | `google`/`microsoft`/`email` |
| `jti` | UUID | yes | session id |
| `iat`, `exp` | numeric | yes | `exp - iat ≤ 1800` (30 min) for PHP-issued sessions |
| **`platformAdmin`** | bool | yes | mirrors server's claim |
| **`orgId`** | UUID | nullable | active organization (selected by user) |
| **`orgRole`** | string | nullable | `ADMIN`/`ENGINEER`/`VIEWER` |
| **`memberships`** | array | yes | `[{orgId, orgName, orgRole}]` for org switcher |

The auth-service must populate `platformAdmin`/`orgId`/`orgRole`/`memberships`. Two options:

- **Option A (recommended) — Server exposes `GET /internal/v1/users/{userId}/identity`** (mTLS or HMAC-protected). Auth-service calls it during `TokenService.createSession()` and embeds the result. Pros: single source of truth in the server's domain DB. Cons: adds one hop on login.
- **Option B — Auth-service reads server's `users` + `org_members` tables read-only.** Pros: no extra hop. Cons: cross-service DB coupling, schema lock-in.

**Choose Option A.** Login is infrequent; per-request cost is unchanged.

### 4.4 Role hierarchy

```
ROLE_PLATFORM_ADMIN  (cross-tenant, ops / "Platform Engineer")
    ⊇ ROLE_ADMIN     (org-scoped full control)
        ⊇ ROLE_ENGINEER (operational write)
            ⊇ ROLE_VIEWER (read-only)
```

Spring Security `RoleHierarchy` bean encodes this in both the gateway and each service.

**Note:** The server currently has `UserRole { PLATFORM_ADMIN, ADMIN, MEMBER, VIEWER }` and `OrgMemberRole { ADMIN, ENGINEER, VIEWER }`. `MEMBER` conflicts with `ENGINEER` in the canonical role list. Retire `UserRole.MEMBER`; treat `OrgMemberRole` as the canonical org-scoped role. Track as a separate cleanup task before Phase 3.

### 4.5 Gateway responsibilities

- Terminate TLS; one public certificate.
- Validate JWT (`iss`, `aud`, `exp`, signature).
- Apply route-level role guard (Spring Security `.authorizeExchange()`).
- Apply global rate-limit (Bucket4j + Redis or in-memory for Phase 1).
- Inject `X-CG-User-Id`, `X-CG-Org-Id`, `X-CG-Role`, `X-Forwarded-For`, `X-Request-Id` headers.
- Forward original `Authorization` header unchanged.
- Emit Micrometer metrics + structured access log per request.
- Reject `X-Acting-As-Org` from non-PLATFORM_ADMIN callers as defence-in-depth (server still re-checks).
- Strip all inbound `X-CG-*` headers before inspection to prevent client forgery.

### 4.6 Service responsibilities (unchanged)

- Each downstream service runs its own JWT filter, builds a domain principal, sets tenant context, enforces ownership.
- The gateway is additive — services remain independently securable.

## 5. Component Topology

```
                        ┌─────────────────────────────────────────┐
                        │          Public Internet                 │
                        │  Browser  React SPA  Java Client  PHP?  │
                        └──────────────────┬──────────────────────┘
                                           │ HTTPS / Bearer JWT
                                           ▼
                              ┌────────────────────────┐
                              │     API Gateway        │
                              │  Spring Cloud Gateway  │
                              │  JWT validate + roles  │
                              │  Rate limit + CORS     │
                              └──┬──────────┬──────────┘
                                 │          │
              /api/auth/**       │          │  /api/v1/**
                                 ▼          ▼
                    ┌──────────────┐  ┌─────────────────┐
                    │ auth-service │  │ certguard-server│
                    │  :8090 STS  │  │  :8443 domain   │
                    └──────────────┘  └─────────────────┘
                                           │  future
                              /api/portal/ │  /api/cmc/**  /api/monitor/**
                                 ▼          ▼          ▼
                          ┌──────────┐ ┌──────────┐ ┌──────────┐
                          │   PHP    │ │CertMgr   │ │certmonit │
                          │  portal  │ │  Cloud   │ │  or      │
                          └──────────┘ └──────────┘ └──────────┘

Agent traffic → separate mTLS listener on certguard-server (bypasses gateway)
```

## 6. PHP Portal Integration

### 6.1 Classification

PHP is treated as **a microservice inside the trust zone**. It is:
- A routed backend at `/api/portal/**` (browser → gateway → PHP).
- A client of other services (PHP → gateway → certguard-server) using the **end-user's** JWT, never a service token.

### 6.2 Auth flow (server-side OAuth)

1. Browser hits PHP `/` (landing page).
2. User clicks "Sign in with Google/Microsoft" or submits email/password.
3. PHP calls `POST /api/auth/initiate` via the gateway, gets the provider auth URL.
4. PHP redirects browser to the provider.
5. Provider redirects back to PHP `/callback?code=...`.
6. PHP calls `POST /api/auth/token` via the gateway.
7. Auth-service exchanges code, enriches with role claims (§4.3), issues JWT, persists session.
8. PHP stores the JWT in its server-side session (backed by Redis or DB).
9. PHP sets an `HttpOnly; Secure; SameSite=Lax` session cookie on the browser.
10. On subsequent API calls, PHP reads the JWT from its session and forwards it as `Authorization: Bearer ...` to the gateway.

The browser **never receives the JWT**. The session cookie references a PHP session ID only.

### 6.3 Purchases flow

- PHP integrates a PSP (Stripe/Adyen). All card data uses a PSP-hosted form (Stripe Elements / Adyen Drop-in). PHP never sees PAN/CVV — the platform stays out of PCI scope.
- On payment success, PHP receives a PSP-signed webhook receipt.
- PHP calls `PATCH /api/v1/organizations/{orgId}/subscription` via the gateway with the user's JWT + `X-Purchase-Receipt: <psp-signed-token>`.
- The server verifies the PSP signature independently before mutating the subscription. The bearer token says *who* is purchasing; the receipt says *what* was paid.

### 6.4 PHP-specific guardrails

- JWT TTL ≤ 30 min when issued via PHP login.
- PHP must call `DELETE /api/auth/session` on user logout.
- PHP DB encrypted at rest; classify as PII zone.
- No service-account token may call routes under `/api/v1/organizations/**` — gateway enforces.
- PHP host network-segmented from public internet except through the gateway.

### 6.5 PHP purchase risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R-PHP-1 | PHP processes payment data — pulls platform into PCI scope if PAN/CVV transits | High | PSP-hosted form only (Stripe Elements / Adyen Drop-in) |
| R-PHP-2 | PHP holds user JWTs in session — PHP host compromise = mass token theft | High | Short TTL (≤30 min) + refresh flow; HTTPOnly+Secure+SameSite cookies; logout revokes session |
| R-PHP-3 | PII in two places (auth_users + PHP billing DB) | Medium | PHP DB encrypted at rest; GDPR deletion cascades from auth-service |
| R-PHP-4 | Session fixation / CSRF on PHP portal | High | Regenerate session ID post-login; SameSite cookies; CSRF tokens on mutating forms |
| R-PHP-5 | Confused deputy — PHP calls gateway on behalf of wrong user | High | PHP always forwards the user's own JWT; no privileged service account for customer routes |
| R-PHP-6 | Purchase receipt forgery | Medium | Server verifies PSP-signed webhook independently; does not trust PHP alone |
| R-PHP-7 | New OAuth redirect URIs needed in Google/MS consoles | Low | Add PHP callback URLs to OAuth app configs before go-live |
| R-PHP-8 | Logout leaves JWT valid until exp | Medium | PHP calls `DELETE /api/auth/session` on logout; `jti` revocation honoured by auth-service |

## 7. Role Propagation

### 7.1 Gateway enforcement (coarse, route-level)

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: auth
          uri: http://auth-service:8090
          predicates: [Path=/api/auth/**]
          # public — no role required

        - id: certguard-read
          uri: http://certguard-server:8443
          predicates:
            - Path=/api/v1/**
            - Method=GET
          metadata: { minRole: ROLE_VIEWER }

        - id: certguard-write
          uri: http://certguard-server:8443
          predicates:
            - Path=/api/v1/**
            - Method=POST,PUT,PATCH,DELETE
          metadata: { minRole: ROLE_ENGINEER }

        - id: certguard-admin
          uri: http://certguard-server:8443
          predicates: [Path=/api/v1/admin/**]
          metadata: { minRole: ROLE_PLATFORM_ADMIN }

        - id: portal
          uri: http://php-portal:8000
          predicates: [Path=/api/portal/**]
          # PHP enforces its own session auth

        - id: certmanagercloud
          uri: http://certmanagercloud:8444
          predicates: [Path=/api/cmc/**]
          metadata: { minRole: ROLE_VIEWER }

        - id: certmonitor
          uri: http://certmonitor:8445
          predicates: [Path=/api/monitor/**]
          metadata: { minRole: ROLE_VIEWER }
```

A custom `JwtRouteAuthorizationFilter` reads `metadata.minRole`, maps JWT claims to Spring Security authorities via `RoleHierarchyImpl`, and rejects with RFC 9457 `ProblemDetail` on insufficient role.

### 7.2 Service enforcement (fine-grained, unchanged)

`certguard-server`'s `JwtAuthenticationFilter`, `CertGuardUserPrincipal`, `TenantContext`, `@PreAuthorize`, and org-scoped query guards remain authoritative for:
- Multi-tenant org boundary enforcement (gateway has no access to the domain DB).
- Ownership checks (target X belongs to org Y).
- `X-Acting-As-Org` impersonation audit (`PlatformAdminAuditService`).
- Subscription-status gates.

## 8. Phased Rollout

### Phase 0 — Prerequisites (1 sprint)
- [ ] Fix `server/src/main/java/com/certguard/config/SecurityConfig.java` to Spring Security 7 DSL.
- [ ] Consolidate duplicate `LoginRequest` DTOs (`controller/LoginRequest.java` vs `dto/LoginRequest.java`).
- [ ] Add `GET /internal/v1/users/{userId}/identity` to certguard-server (HMAC-secured), returning `{userId, email, platformAdmin, orgId, orgRole, memberships}`.
- [ ] Extend `UnifiedTokenProvider.issue()` to accept and embed the new claims.
- [ ] Extend `TokenService.createSession()` to call the server identity endpoint.
- [ ] Server's `JwtAuthenticationFilter` accepts BOTH `iss=certguard-auth` AND `iss=certguard-cloud` during transition.
- [ ] Make `last_used_at` write in `TokenService.validate()` async (current sync write is a future hotspot).
- [ ] Add `POST /api/auth/refresh` to auth-service.

### Phase 1 — Gateway deployed, no PHP, no token cutover (1–2 sprints)
- [ ] Create `certguard-gateway` Spring Cloud Gateway service.
- [ ] Route `/api/auth/**` → auth-service, `/api/v1/**` → certguard-server.
- [ ] React SPA: flip `VITE_API_BASE_URL` to gateway origin.
- [ ] Update Google/MS OAuth console redirect URIs to gateway-hosted callbacks.
- [ ] Server's existing `/api/v1/auth/dev-token` and `/api/auth/login` continue to work (dual-issuer accepted by server filter).
- [ ] Acceptance: full UI regression passes via gateway; agents continue unaffected.

### Phase 2 — PHP portal onboarding (2 sprints)
- [ ] Deploy PHP portal service. Route `/api/portal/**` to it.
- [ ] PHP auth flow via gateway (§6.2).
- [ ] PHP PSP integration, purchase endpoint on certguard-server.
- [ ] Acceptance: end-to-end customer signup → login via PHP → purchase → subscription updated.

### Phase 3 — Token unification cutover (1 sprint)
- [ ] React SPA switches login from server's `/api/auth/login` to auth-service's `/api/auth/token`.
- [ ] `DevAuthController` migrated behind `app.dev-mode=true` flag or moved to auth-service.
- [ ] Server's `AuthController.login()` deprecated; validates tokens issued in the overlap window.
- [ ] Monitor: zero tokens with `iss=certguard-cloud` for 7 consecutive days.

### Phase 4 — Server auth cleanup (1 sprint)
- [ ] Delete `AuthController.login()`, duplicate `LoginRequest`, `JwtTokenProvider.generateToken()`.
- [ ] Server filter rejects `iss != certguard-auth`.
- [ ] Update HLD, LLD, GAPS.

### Phase 5 — RS256 + JWKS (target state, ≤ 6 months)
- [ ] Generate RSA keypair; store private key in secret manager.
- [ ] Auth-service publishes `GET /.well-known/jwks.json`.
- [ ] Gateway and all services switch to JWKS-based validation with rotation.
- [ ] PHP validates offline with public key (`web-token/jwt-framework`).

## 9. New Gaps (add to GAPS.md)

| ID | Description | Severity | Phase to fix |
|---|---|---|---|
| G1 | Auth-service JWT has no role claims; server filter requires `platformAdmin`/`orgRole` — **blocker for cutover** | Critical | Phase 0 |
| G2 | `UserRole.MEMBER` vs `OrgMemberRole.ENGINEER` mismatch — inconsistent role enum | High | Phase 3 |
| G3 | Two token issuers (`iss=certguard-auth` and `iss=certguard-cloud`) — server currently only accepts its own | High | Phase 0 (dual-accept), Phase 4 (cleanup) |
| G4 | `TokenService.validate()` writes `last_used_at` synchronously — write hotspot under PHP introspection load | Medium | Phase 0 |
| G5 | No `POST /api/auth/refresh` endpoint despite `refresh_token` column in schema | Medium | Phase 0 |
| G6 | `server/SecurityConfig.java` uses Spring Security 5 DSL — won't compile on Spring Boot 4.0.3 | Critical | Phase 0 |

## 10. Open Questions

- **OQ1.** Org-switcher UX: should the JWT embed all memberships and the UI picks one, or should re-issuing on org-switch be required? Recommendation: embed memberships, pass `X-Active-Org-Id` header validated server-side.
- **OQ2.** Refresh token: add `POST /api/auth/refresh` in Phase 0 or Phase 2?
- **OQ3.** PHP customer profile ownership: does PHP own `customer_profile` (billing address, name) or sync from certguard-server? Recommendation: PHP owns purchase-domain data only; identity stays in `auth_users`.
- **OQ4.** Single PHP portal or multiple (per-region/brand)? Affects redirect-URI count and CORS policy.

## 11. Rejected Alternatives

- **Auth-service-as-gateway** — merges issuing and routing concerns, wrong scaling profile, adds every service's route config to auth release cycles, creates a single blast-radius failure for the whole platform.
- **NGINX + Lua JWT** — less extensible for role enforcement and future plugin needs.
- **No gateway, direct per-service URLs** — untenable once CertManagerCloud and certmonitor join; duplicates CORS, rate limits, and observability in every service.
- **Service mesh (Istio)** — too heavy for current footprint; revisit when service count > 5.

## 12. Acceptance Criteria

- **Phase 1:** Full UI regression passes against gateway; `docs/perf/k6-api-smoke.js` and `docs/perf/postman-collection.json` pass with no regressions; agents unaffected.
- **Phase 2:** End-to-end customer signup → Google OAuth via PHP → make purchase → certguard-server subscription updated in staging.
- **Phase 3:** Production telemetry shows zero `iss=certguard-cloud` tokens issued for 7 consecutive days.
- **Phase 4:** All server-side token-issuance code deleted; CI passes cleanly.
- **Phase 5:** All services validate via JWKS; key rotation drill completed in staging without downtime.
