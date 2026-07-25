# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Layout

This is **not** a multi-module Maven build — it is a working directory containing several independently-buildable services plus the frontend and design docs:

- `server/` — CertGuard Cloud server (Spring Boot 4.0.3, Java 25 LTS). Own `pom.xml`, `Dockerfile`, `docker-compose.yml`.
- `agent/` — CertGuard Agent (plain Java 25 LTS, no Spring). Own `pom.xml` and a `certguard-agent.service` systemd unit.
- `ui/` — React 19 + Vite 7 dashboard, consuming `server`'s `/api/v1/...` API. npm/Vite, not Maven.
- `certguard-gateway/` — edge reverse-proxy/gateway service (`JwtValidationFilter`, `ReverseProxyFilter`) that routes and authenticates inbound traffic in front of `server`. Own `pom.xml`, `Dockerfile`, `docker-compose.yml`.
- `certguard-auth-service/` — user authentication/identity service: Google/Microsoft/email login, JWT issuance, JWKS endpoint. Own `pom.xml`, `Dockerfile`.
- `certguard-renewal-service/` — automates CA-issued certificate renewal (CSR submission, CA provider registry, delivery of renewed packages). Own `pom.xml`, `Dockerfile`.
- `docs/architecture/{HLD.md,LLD.md,GAPS.md}` — authoritative design docs. `docs/BACKEND_REVIEW.md`, `docs/TEST_PLAN.md`.
- `docs/perf/` — `k6-api-smoke.js` and `postman-collection.json` for API/perf testing.

The parent `/home/msuman/git/CLAUDE.md` applies to the broader monorepo; this file is specific to the services in this repo.

## Build & Run

Server (`cd server`):
```bash
mvn clean install
mvn test
mvn test -Dtest=ClassName#methodName
mvn spring-boot:run
docker-compose up -d         # postgres + rabbitmq + server
```

Agent (`cd agent`):
```bash
mvn clean package            # produces runnable JAR
java -jar target/certguard-agent-*.jar
```

Gateway (`cd certguard-gateway`):
```bash
mvn clean install
mvn spring-boot:run
```

Auth service (`cd certguard-auth-service`):
```bash
mvn clean install
mvn spring-boot:run
```

Renewal service (`cd certguard-renewal-service`):
```bash
mvn clean install
mvn spring-boot:run
```

UI (`cd ui`):
```bash
npm install
npm run dev        # dev server
npm run build      # production build
npm run lint       # eslint
```

Perf / API smoke:
```bash
k6 run docs/perf/k6-api-smoke.js
```

## Architecture (read before changing code)

Six deployable pieces. The core pair is still a multi-tenant **cloud server** plus **self-hosted agents** that scan private-network TLS endpoints the server cannot reach; around them sit the **gateway** (edge auth/routing in front of the server), the **auth-service** (login + JWT issuance), the **renewal-service**, and the **React UI**.

**Auth topology:** auth-service signs user JWTs with RS256; the gateway validates them (`JwtValidationFilter`) and forwards trusted identity to the server as `X-CG-*` headers. The gateway 401s any request that is neither a valid user JWT nor matched by its `PUBLIC_PATTERNS` allowlist — when adding a new endpoint that agents or anonymous flows must reach, check whether the gateway needs a pattern entry, or it will be rejected before the server ever sees it. Outbound traffic (SMTP, webhooks) does not traverse the gateway.

**Server** (`com.certguard.*`) follows the standard three-layer Spring pattern: `controller → service → repository → PostgreSQL`. Notable pieces:
- `security/` has five auth filters on the `SecurityFilterChain`: `JwtAuthenticationFilter` for user/UI traffic (trusts the gateway-injected `X-CG-*` identity headers — the gateway strips any client-supplied ones before injecting; when those headers are absent it falls back to validating a bare JWT directly, so the server authenticates without the gateway too — used by local dev, and relevant when reasoning about gateway bypass), `AgentAuthFilter` for agent traffic (`X-Agent-Key`/`X-Agent-Id` headers verified with BCrypt — not mTLS at this layer), plus `AnonScanAuthFilter`, `InternalServiceAuthFilter`, and `SalesAuthFilter`. HMAC signing lives in `AgentHmacService` (used by `AgentService`), not in the request-auth path.
- `AgentCertificateAuthority` (BouncyCastle) issues per-agent client certs used for mTLS enrollment.
- Schedulers drive async work (`ScheduledPublicScan`, `CertificateExpiryScheduler`, `AgentOfflineScheduler`, `PrivateScanScheduler`, `RevocationRecheckScheduler`, `NotificationOutboxScheduler`, stale-claimed-job reset). All periodic jobs take `@SchedulerLock` (ShedLock, JDBC provider) for multi-replica safety — keep new periodic work in this package and follow that pattern.
- **Notification delivery is outbox-based** (R19 fix): alert dispatch inserts a `notification_outbox` row in the *same transaction* as the dedup stamp; `NotificationOutboxScheduler` drains PENDING rows with capped exponential backoff and purges SENT/FAILED rows on retention windows. `NotificationService.sendOutboxEmail` is `@Transactional(NOT_SUPPORTED)` — load-bearing (the class default is `readOnly=true`; joining the drain transaction turns a send failure into a silent rollback of the retry bookkeeping). Do not remove it; there is a Testcontainers regression test pinning this.
- Schema is owned by Flyway SQL migrations in `src/main/resources/db/migration/V*__*.sql` — Hibernate runs with `ddl-auto: none`. Add a new `Vn__*.sql` file; do not let JPA create tables. Avoid `CREATE INDEX CONCURRENTLY` in migrations — it hangs, and `executeInTransaction=false` does NOT fix it: Flyway runs off the app's shared connection pool (`FlywayConfig`) and holds its migration lock open on one connection; CONCURRENTLY waits on all other open transactions, including that one. See V44's comment block and task #15. `FlywayConfig` wires a manual, unconditional `@Bean(initMethod="migrate")` — Spring Boot 4 dropped Flyway autoconfig, so `spring.flyway.enabled` is not read and Flyway migrations run for real in every Testcontainers `@SpringBootTest`. Most repo/integration tests then opt into `ddl-auto=create-drop` per-test via `@DynamicPropertySource`, which discards migration-only DDL (indexes/CHECK/triggers/FKs not mapped as a JPA association) — so those constructs still need manual validation against a real database (GAPS R24). A few tests (e.g. `NotificationOutboxIndexExplainTest`) deliberately keep `ddl-auto=none` to assert migration DDL directly.
- RabbitMQ is wired in docker-compose but marked "Phase 3 ready" — don't assume it's in the hot path yet; confirm in code before relying on it.
- Static assets under `src/main/resources/static/` are the packaged UI; `src/main/resources/agent/` holds agent-distribution artifacts served to enrolled agents.

**Testing note:** integration tests use Testcontainers and need a reachable Docker daemon; the surefire config pins `-Dapi.version=1.44` (docker-java otherwise negotiates an API version modern daemons reject — without the pin the whole integration layer silently skips and the build still reports green). Run only one Maven build in `server/` at a time; concurrent builds share `target/` and corrupt each other. Each test's schema comes from Flyway *and then* whatever `ddl-auto` it opts into via `@DynamicPropertySource` — see the Flyway/`FlywayConfig` note above before assuming a test does or doesn't exercise a given migration's DDL.

**Agent** (`com.certguard.agent.*`) is deliberately framework-free (plain Java + Apache HttpClient 5 + BouncyCastle + Jackson + Logback). Structure:
- `config/` loads `application.properties`, `security/` handles mTLS keystore + `HmacSigner`, `http/SecureHttpClient` pins TLS 1.3 and the server cert, `scanner/SslScanner` performs the TLS handshake against targets, and `AgentMain` runs the poll loop that claims jobs, scans, and reports back.
- Do **not** introduce Spring, Lombok, or heavy deps here — the agent ships as a small JAR customers run on-prem.

**Multi-tenancy & data model:** org-scoped; URL hierarchy enforces tenant boundaries (e.g. `/api/v1/organizations/{orgId}/...`). UUID primary keys, RFC 9457 `ProblemDetail` error bodies, class-level `@Transactional(readOnly=true)` with write methods overriding. Some child tables denormalize `org_id` alongside their parent FK for query performance — preserve that when adding tables.

**Ground truth for cross-cutting questions:** `docs/architecture/HLD.md` (system context, component diagram) and `LLD.md`. `GAPS.md` tracks known holes — check it before claiming a feature is missing.
