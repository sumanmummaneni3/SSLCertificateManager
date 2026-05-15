# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Layout

This is **not** a multi-module Maven build — it is a working directory containing two independent source drops plus design docs:

- `certguard-server-source-20260419-0737/` — CertGuard Cloud server (Spring Boot 4.0.3, Java 25 LTS). Own `pom.xml`, `Dockerfile`, `docker-compose.yml`.
- `certguard-agent-source-20260419-0737/` — CertGuard Agent (plain Java 25 LTS, no Spring). Own `pom.xml` and a `certguard-agent.service` systemd unit.
- `docs/architecture/{HLD.md,LLD.md,GAPS.md}` — authoritative design docs. `docs/BACKEND_REVIEW.md`, `docs/TEST_PLAN.md`.
- `docs/perf/` — `k6-api-smoke.js` and `postman-collection.json` for API/perf testing.
- The `*.zip` files are snapshots of the two source trees; edit the unzipped directories.

The parent `/Users/suman/git/CLAUDE.md` applies to the broader monorepo; this file is specific to the server+agent pair here.

## Build & Run

Server (`cd certguard-server-source-20260419-0737`):
```bash
mvn clean install
mvn test
mvn test -Dtest=ClassName#methodName
mvn spring-boot:run
docker-compose up -d         # postgres + rabbitmq + server
```

Agent (`cd certguard-agent-source-20260419-0737`):
```bash
mvn clean package            # produces runnable JAR
java -jar target/certguard-agent-*.jar
```

Perf / API smoke:
```bash
k6 run docs/perf/k6-api-smoke.js
```

## Architecture (read before changing code)

Two-process system: a **cloud server** (multi-tenant SaaS) and **self-hosted agents** that scan private-network TLS endpoints the server cannot reach.

**Server** (`com.certguard.*`) follows the standard three-layer Spring pattern: `controller → service → repository → PostgreSQL`. Notable pieces:
- `security/` has two filters on the `SecurityFilterChain`: `JwtAuthFilter` for user/UI traffic and `AgentAuthFilter` for agent traffic (mTLS + HMAC-signed requests).
- `AgentCertificateAuthority` (BouncyCastle) issues per-agent client certs used for mTLS enrollment.
- Schedulers drive async work: `ScheduledPublicScan` (direct scans of public targets), `CertificateExpiryScheduler` (notifications), `AgentOfflineScheduler`, and a stale-claimed-job reset job. Keep new periodic work in this package.
- Schema is owned by Flyway-style SQL migrations in `src/main/resources/db/migration/V*__*.sql` — Hibernate runs with `ddl-auto: validate`. Add a new `Vn__*.sql` file; do not let JPA create tables.
- RabbitMQ is wired in docker-compose but marked "Phase 3 ready" — don't assume it's in the hot path yet; confirm in code before relying on it.
- Static assets under `src/main/resources/static/` are the packaged UI; `src/main/resources/agent/` holds agent-distribution artifacts served to enrolled agents.

**Agent** (`com.certguard.agent.*`) is deliberately framework-free (plain Java + Apache HttpClient 5 + BouncyCastle + Jackson + Logback). Structure:
- `config/` loads `application.properties`, `security/` handles mTLS keystore + `HmacSigner`, `http/SecureHttpClient` pins TLS 1.3 and the server cert, `scanner/SslScanner` performs the TLS handshake against targets, and `AgentMain` runs the poll loop that claims jobs, scans, and reports back.
- Do **not** introduce Spring, Lombok, or heavy deps here — the agent ships as a small JAR customers run on-prem.

**Multi-tenancy & data model:** org-scoped; URL hierarchy enforces tenant boundaries (e.g. `/api/v1/organizations/{orgId}/...`). UUID primary keys, RFC 9457 `ProblemDetail` error bodies, class-level `@Transactional(readOnly=true)` with write methods overriding. Some child tables denormalize `org_id` alongside their parent FK for query performance — preserve that when adding tables.

**Ground truth for cross-cutting questions:** `docs/architecture/HLD.md` (system context, component diagram) and `LLD.md`. `GAPS.md` tracks known holes — check it before claiming a feature is missing.
