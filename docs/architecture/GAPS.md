# CertGuard — Gaps, Discrepancies, and Risks

Anchored against the 2026-04-19 source drop (`certguard-server-source-20260419-0737/`, `certguard-agent-source-20260419-0737/`, `/Users/suman/git/certguard-ui/`). Companion to `HLD.md` / `LLD.md`.

## 1. Doc-vs-Code Discrepancies

| # | Area | Doc says | Code says | Evidence |
|---|---|---|---|---|
| D1 | PostgreSQL version | `CLAUDE.md` references Postgres 16 | Runtime compose uses `postgres:15-alpine`; only Testcontainers uses `postgres:16-alpine` | `certguard-server-source-20260419-0737/docker-compose.yml:7` vs `src/test/java/com/certguard/controller/TargetControllerRbacTest.java:52` |
| D2 | AMQP / RabbitMQ | HLD §2 shows RabbitMQ in component graph and deployment topology | No `@RabbitListener`, `RabbitTemplate`, or publisher in server source; dependency declared but inert | HLD.md:54,66,215; `pom.xml` amqp starter present, no usage found in `src/main/java` |
| D3 | Heartbeat → Jobs ordering | HLD §3.4 shows agent `POST /heartbeat` then `GET /jobs` in same loop tick | `PollLoop` tick calls `getJobs()` first; heartbeat is implicit via `last_seen_at` bump in `AgentAuthFilter` on every authenticated call | `certguard-agent-source-20260419-0737/.../PollLoop.java:46-124`, `AgentAuthFilter.java:92-97` |
| D4 | Invitation endpoint | LLD §2 lists `POST /api/v1/org/invitations` under OrgController | Endpoint lives on `TeamController` at `@PostMapping("/invitations")` | `TeamController.java:33` |
| D5 | UI coverage | Docs reference a React SPA with dashboard, targets, teams, admin views | `src/App.jsx` is default Vite scaffold; real app is monolithic `src/certguard-ui.jsx` with hard-coded `API_BASE` and `DEV_MODE = true`; no router, no API client module | `/Users/suman/git/certguard-ui/src/certguard-ui.jsx:1-10` |

## 2. Architectural Risks

### R1 — mTLS is symbolic (high)
Server issues X.509 client certs via `AgentCertificateAuthority`, but:
- Tomcat connector does not require `clientAuth`; auth is bearer `X-Agent-Id` + `X-Agent-Key` (`AgentAuthFilter.java:57-109`).
- Agent loads client cert without a private key (`SecureHttpClient.java:79-107`, `kmf.init(ks, null)`), so mutual TLS cannot complete.
- The cert PEM in `agents.client_cert_pem` is decorative.

### R2 — Hostname verification disabled on agent (high)
`SecureHttpClient.java:54-57` installs `NoopHostnameVerifier`. With fingerprint pinning this is workable; when `trust-self-signed=true` and fingerprint blank, there is no trust validation at all — full MitM exposure.

### R3 — `APP_DEV_MODE=true` default (high)
`application.yml` defaults `app.dev-mode: true`, which bypasses Google OAuth, enables `POST /api/v1/auth/dev-token` (any email mints a JWT — `DevAuthController.java:48-86`), and short-circuits `NotificationService` email sends. A mis-configured prod container silently becomes an open auth surface.

### R4 — Flyway `repair()` + `validateOnMigrate(false)` (medium)
`FlywayConfig.java:10-25` calls `repair()` on every boot and disables validation. Checksum drift and out-of-order migrations go undetected — schema can silently diverge from VCS.

### R5 — CORS wildcard with credentials (medium)
`SecurityConfig.java:90-94` sets `allowedOriginPatterns=*` together with `allowCredentials=true`. Effectively permits any origin to make credentialed requests.

### R6 — In-memory invitation OTP store (medium)
`InvitationService.java:55-57` keeps OTP state in a `ConcurrentHashMap`. Any multi-instance deployment loses pending OTPs.

### R7 — Agent job-claim race (medium)
`AgentService.java:136-144` finds then saves without DB-level locking. Should use `SELECT … FOR UPDATE SKIP LOCKED` or atomic `UPDATE … RETURNING`.

### R8 — JWT revocation absent (medium)
24h HS256 JWT with no refresh, blacklist, or jti tracking. Role demotion and account removal cannot invalidate outstanding tokens.

### R9 — RabbitMQ provisioned but unused (low–medium)
Compose runs RabbitMQ but no code uses AMQP. Operational surface for no current function.

### R10 — Error model inconsistency (low)
`AgentAuthFilter` writes raw `{"error":...}` 401; `DevAuthController` returns `Map.of("error",…)`, bypassing `ProblemDetail`.

### R11 — UI fragility (medium)
Single-file `certguard-ui.jsx` with hard-coded `API_BASE`, embedded CSS, `DEV_MODE = true` constant, no routing/state/API abstraction.

## 3. Prioritized Recommendations

**P0 — ship-stoppers**
1. Default `app.dev-mode` to `false`; require explicit opt-in and log a WARN banner on startup (R3).
2. Decide mTLS: either enforce `clientAuth=need` and have agent retain its private key (validate chain in `AgentAuthFilter`), or drop the CA path and rely on pinned-fingerprint TLS + bearer (R1).
3. Gate `trust-self-signed` behind a non-default profile; refuse startup if fingerprint empty AND trust-self-signed true in non-dev (R2).

**P1 — correctness**
4. Replace Flyway `repair()`-on-boot with explicit ops tooling; restore `validateOnMigrate=true` (R4).
5. Tighten CORS to an allowlist; forbid `*` when `allowCredentials=true` (R5).
6. Move OTP store to Redis or an `invitation_otp` table with TTL (R6).
7. Convert job claim to `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)` (R7).
8. Add JWT `jti` + revocation table, or switch to short-lived access + refresh (R8).

**P2 — hygiene**
9. Standardize error outputs on `ProblemDetail`; remove ad-hoc maps (R10).
10. Either adopt RabbitMQ for scan dispatch or remove it from compose + `pom.xml` (R9, D2).
11. Align runtime and test Postgres versions — pick 16, bump compose (D1).
12. Fix LLD invitation row to `TeamController` (D4); amend HLD agent-scan sequence to show implicit heartbeat (D3).
13. Refactor `certguard-ui.jsx` into feature folders, introduce `apiClient.js`, read `API_BASE` from `import.meta.env.VITE_API_BASE` (R11, D5).

## 4. Open Questions

- Is RabbitMQ intended for Phase-3 scan dispatch, or vestigial? (R9 / D2)
- Is the agent expected to hold its own private key (true mTLS), or is X-Agent-Key + pinned TLS the long-term design? (R1)
- What is the multi-instance deployment target for the server? (R6 / R8)
- Is Vite scaffold `App.jsx` dead code, or does `main.jsx` mount `certguard-ui.jsx` directly? (D5)
