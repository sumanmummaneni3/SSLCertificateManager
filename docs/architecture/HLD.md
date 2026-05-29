# CertGuard — High-Level Design

> **Note — source paths.** Earlier revisions of this document reference
> `certguard-server-source-20260419-0737/...` and
> `certguard-agent-source-20260419-0737/...`. The live source tree is now at
> `server/...` and `agent/...` respectively. When following a citation, resolve
> the old prefix to the new one (e.g. `certguard-server-source-20260419-0737/src/main/java/...`
> → `server/src/main/java/...`).

## 1. System Context

CertGuard now runs as a three-process control plane behind a single nginx-fronted gateway, plus N self-hosted scanning agents.

```mermaid
C4Context
  title CertGuard — System Context (gateway + auth-service)
  Person(user, "Org User / Admin / Platform Admin", "Browser + SPA")
  Person(invitee, "Invited Member", "Accepts invite via email OTP")
  System_Boundary(cg, "CertGuard Platform") {
    System(gw, "CertGuard Gateway", "Spring Cloud Gateway behind nginx; validates RS256 JWT against auth-service JWKS, strips client-supplied X-CG-* headers, injects trusted X-CG-* headers, proxies to upstreams")
    System(auth, "CertGuard Auth Service", "Spring Boot — OIDC (Google/Microsoft), local OTP invites, RS256 JWT issuance, JWKS endpoint")
    System(server, "CertGuard Core Server", "Spring Boot 4.0.x — domain logic; trusts gateway-injected X-CG-* headers, falls back to HS256 JWT in dev only")
    System(agent, "CertGuard Agent", "Plain Java, on customer LAN — bundle-installed, polls server")
    SystemDb(pg, "PostgreSQL", "certguard_auth + certguard databases")
  }
  System_Ext(google, "Google OIDC", "OAuth2 login")
  System_Ext(ms, "Microsoft Entra ID", "OAuth2 login")
  System_Ext(smtp, "SMTP", "Email delivery")
  System_Ext(public, "Public TLS targets", "Internet endpoints")
  System_Ext(private, "Private TLS targets", "Customer LAN")

  Rel(user, gw, "HTTPS 443", "Browser + REST")
  Rel(gw, auth, "HTTP 8090 (login, JWKS)")
  Rel(gw, server, "HTTPS 8443 + X-CG-* headers")
  Rel(auth, google, "OIDC")
  Rel(auth, ms, "OIDC")
  Rel(server, smtp, "SMTP STARTTLS")
  Rel(server, public, "TLS handshake (direct scan)")
  Rel(agent, gw, "HTTPS poll (bundle-bootstrapped)")
  Rel(agent, private, "TLS handshake (agent scan)")
  Rel(server, pg, "JDBC")
  Rel(auth, pg, "JDBC")
```

**Process roles** (anchored in `server/docker-compose.yml:107-210`):

- **nginx** — TLS termination on :443; reverse-proxies to the `gateway` container.
- **gateway** (`certguard-gateway` image, lines 185-210) — Spring Cloud Gateway. Validates RS256 JWTs against auth-service JWKS, strips all client-supplied `X-CG-*` headers, injects trusted `X-CG-User-Id / X-CG-Org-Id / X-CG-Role / X-CG-Email / X-CG-Platform-Admin` headers, then proxies to the core server.
- **auth-service** (`certguard-auth-service` image, lines 107-146) — owns user identity, OAuth callbacks, JWT minting. Signs JWTs with RS256 private key; exposes public half at `/api/auth/.well-known/jwks.json`.
- **app** (the certguard-server) — domain logic server. `JwtAuthenticationFilter` trusts the gateway-injected `X-CG-*` headers; only falls back to local HS256 parsing in dev/direct-access paths.
- **agent** — self-hosted, talks to the gateway origin only (bundle pins the URL at install time).

### Security note — header trust boundary (MUST READ)

The `X-CG-*` headers are trust tokens between the gateway and the core server. The contract is:

1. **The gateway MUST unconditionally strip every `X-CG-*` header on every inbound request** before its JWT-validation filter runs, regardless of method, route, or upstream. Failing to strip allows any unauthenticated caller to impersonate any user by sending forged headers through nginx → gateway.
2. After successful RS256 validation, the gateway injects the five trusted headers. The core server reads them in `server/src/main/java/com/certguard/security/JwtAuthenticationFilter.java:69-75`.
3. **The core server's port 8443 MUST NOT be reachable from outside the Docker network.** The compose `app` service binds to `127.0.0.1` only — if that leaks publicly, a direct connection bypasses both nginx and the gateway.
4. Dev fallback: when `APP_DEV_MODE=true`, the server falls back to local HS256 `Authorization: Bearer` parsing. This path must be disabled in production.
5. `X-Acting-As-Org` and `X-Acting-As-Reason` are forwarded by the gateway unchanged. The server gates them to `PLATFORM_ADMIN` only and requires `X-Acting-As-Reason` on write methods (`JwtAuthenticationFilter.java:131-175`). See ADR-0007.

### Gateway-authenticated API request — sequence

```
Browser         nginx        gateway               auth-service       core-server (app)      Postgres
   |              |             |                       |                    |                   |
   | HTTPS /api   |             |                       |                   |                   |
   | Bearer RS256 |             |                       |                   |                   |
   |------------->|             |                       |                   |                   |
   |              | proxy_pass  |                       |                   |                   |
   |              |------------>|                       |                   |                   |
   |              |             | 1. STRIP X-CG-* hdrs  |                   |                   |
   |              |             | 2. fetch JWKS (cached)|                   |                   |
   |              |             |---------------------->|                   |                   |
   |              |             |<----------------------|                   |                   |
   |              |             | 3. verify RS256       |                   |                   |
   |              |             | 4. INJECT trusted:    |                   |                   |
   |              |             |    X-CG-User-Id       |                   |                   |
   |              |             |    X-CG-Org-Id        |                   |                   |
   |              |             |    X-CG-Role          |                   |                   |
   |              |             |    X-CG-Email         |                   |                   |
   |              |             |    X-CG-Platform-Admin|                   |                   |
   |              |             | 5. proxy upstream     |                   |                   |
   |              |             |------------------------------------------------->|            |
   |              |             |                       |    JwtAuthenticationFilter:            |
   |              |             |                       |      read X-CG-* headers              |
   |              |             |                       |      TokenRevocationService.isRevoked? |
   |              |             |                       |      revoked? -> 401                  |
   |              |             |                       |    controller -> service -> repository |
   |              |             |                       |                            |---------->|
   |              |             |                       |                            |<----------|
   |              |             |<-------------------------------------------------|            |
   |              |<------------|                       |                   |                   |
   |<-------------|             |                       |                   |                   |
```

Anchors: gateway env vars `server/docker-compose.yml:185-210`; header trust + revocation `server/src/main/java/com/certguard/security/JwtAuthenticationFilter.java:66-175`; PA audit `JwtAuthenticationFilter.java:207-214`.

## 2. Component Diagram

```mermaid
graph TB
  subgraph Browser
    UI[React SPA]
  end
  subgraph EdgeLayer["Edge / Control Plane"]
    NGX[nginx :443<br/>TLS termination]
    GW[CertGuard Gateway<br/>Spring Cloud Gateway<br/>strips X-CG-*, validates RS256,<br/>injects X-CG-User-Id/Org-Id/Role/Email/Platform-Admin]
    AUTH[CertGuard Auth Service<br/>:8090<br/>OAuth2 OIDC Google/Microsoft<br/>RS256 mint + JWKS<br/>Invitation OTP]
  end
  subgraph CoreServer["CertGuard Core Server (Spring Boot)"]
    SEC[SecurityFilterChain<br/>JwtAuthFilter trusts X-CG-* + HS256 fallback dev only<br/>AgentAuthFilter for /agent/*<br/>SalesAuthFilter for /api/internal/v1/sales/*]
    REVOKE[TokenRevocationService<br/>Caffeine + RevokedToken table]
    PA_AUDIT[PlatformAdminAuditService<br/>async write per acting-as request]
    CTRL[Controllers:<br/>Agent/AgentProvision/Target/Certificate/<br/>Team/Org/MspClient/Location/Admin/Sales]
    SVC[Services:<br/>TargetService, AgentService, AgentBundleService,<br/>SslScannerService, NotificationService, InvitationService,<br/>CertificateService, TeamService, OrgAuditService,<br/>SubscriptionGuard]
    SCHED[Schedulers ShedLock:<br/>ScheduledPublicScan, CertificateExpiryScheduler,<br/>AgentOfflineScheduler, resetStaleClaimedJobs,<br/>cleanupExpiredInstallKeys, TokenRevocationService.cleanupExpired]
    REPO[(JPA Repositories)]
  end
  subgraph Agent["CertGuard Agent (JAR, bundle-installed)"]
    BU[BundleUnsealer<br/>Argon2id + AES-GCM]
    AM[AgentMain]
    PL[PollLoop]
    API[ServerApiClient]
    HTTP[SecureHttpClient TLS1.3 + pin]
    SC[SslScanner]
    SIG[HmacSigner]
    CFG[(application.properties)]
  end
  PG[(PostgreSQL 16<br/>certguard + certguard_auth)]
  SMTP[SMTP]

  UI -->|HTTPS + RS256 Bearer| NGX --> GW
  GW -- "login/callback" --> AUTH
  GW -- "X-CG-* headers + body" --> SEC
  AUTH --> PG
  SEC --> REVOKE
  SEC --> PA_AUDIT
  SEC --> CTRL --> SVC --> REPO --> PG
  SVC --> SMTP
  BU --> CFG
  AM --> PL --> API --> HTTP
  PL --> SC
  PL --> SIG
  API -->|X-Agent-Id/Key + HMAC| GW --> SEC
```

Anchors: filter wiring `server/src/main/java/com/certguard/security/JwtAuthenticationFilter.java:42-202`; bundle flow `server/src/main/java/com/certguard/service/AgentBundleService.java:102-236` and `server/src/main/java/com/certguard/controller/AgentProvisionController.java:48-84`; bundle decrypt `agent/src/main/java/com/certguard/agent/security/BundleUnsealer.java:69-266`.

## 3. Sequence Diagrams

### 3.1 Google OAuth Login

```mermaid
sequenceDiagram
  participant U as User
  participant B as Browser
  participant S as Server
  participant G as Google
  participant DB as Postgres
  U->>B: Navigate /oauth2/authorization/google
  B->>S: GET /oauth2/authorization/google
  S->>G: Redirect authorize
  G-->>B: code
  B->>S: GET /login/oauth2/code/google?code=...
  S->>G: token + userinfo
  S->>S: OAuth2AuthenticationSuccessHandler.onAuthenticationSuccess
  S->>DB: findByEmail → auto-provision Org + Subscription + User
  S->>S: JwtTokenProvider.generateToken
  S-->>B: 302 {baseUrl}/?token=<jwt>&newUser=true
```

Anchors: `OAuth2AuthenticationSuccessHandler.java:45-138`, `JwtTokenProvider.java:29-38`.

### 3.2 Add Target

```mermaid
sequenceDiagram
  participant UI
  participant TC as TargetController
  participant TS as TargetService
  participant AR as AgentRepository
  participant DB as Postgres
  UI->>TC: POST /api/v1/targets (Bearer JWT)
  TC->>TS: createTarget(orgId, req)
  TS->>DB: enforceTargetQuota (subscription.max_certificate_quota)
  TS->>DB: existsByOrgHostPort (dedupe)
  alt isPrivate && agentId set
    TS->>AR: findByIdAndOrg; check currentTargetCount < maxTargets
    TS->>TS: validateHostInAgentCidrs
  end
  TS->>DB: save Target, inc agent.current_target_count
  TS-->>UI: 201 TargetResponse
```

Anchors: `TargetController.java:35-39`, `TargetService.java:47-106`.

### 3.3 Direct Scan (public target)

```mermaid
sequenceDiagram
  participant UI
  participant TC as TargetController
  participant TS as TargetService
  participant SSL as SslScannerService
  participant DB
  UI->>TC: POST /api/v1/targets/{id}/scan
  TC->>TS: triggerScan
  TS->>SSL: scanTarget(target)   %% target.isPrivate=false
  SSL->>SSL: fetchCertificateChain (trust-all, SNI)
  SSL->>DB: upsert CertificateRecord, set status (VALID/EXPIRING/EXPIRED)
  SSL-->>TS: done
  TS-->>UI: 200 {"message":"Scan triggered ..."}
```

Anchors: `TargetService.java:149-168`, `SslScannerService.java:69-104`.

### 3.4 Agent-based Scan (private target)

```mermaid
sequenceDiagram
  participant UI
  participant TS as TargetService
  participant AS as AgentService
  participant DB
  participant A as Agent
  UI->>TS: POST /targets/{id}/scan (private)
  TS->>AS: queueScanJob(target)
  AS->>DB: insert agent_scan_jobs (PENDING) if none already PENDING/CLAIMED
  loop every pollIntervalSeconds
    A->>+Server: POST /api/v1/agent/heartbeat
    A->>Server: GET /api/v1/agent/jobs
    Server->>DB: findPendingJobsForAgent → mark CLAIMED
    Server-->>A: [{jobId, targetId, host, port, lastKnownSerialHash}]
    A->>A: SslScanner.scan (+ HmacSigner.sign)
    A->>Server: POST /api/v1/agent/results (X-Agent-Id/Key + hmacSignature)
    Server->>Server: AgentHmacService.verify
    Server->>DB: upsert CertificateRecord (FULL) or update (DELTA); job COMPLETED
  end
```

Anchors: `TargetService.java:150-167`, `AgentService.java:136-237`, `PollLoop.java:46-124`, `ServerApiClient.java:74-173`, `AgentHmacService.java:19-38`, `HmacSigner.java:20-30`.

Stale-job recovery: `AgentService.resetStaleClaimedJobs` resets `CLAIMED` > 10 min back to `PENDING` every 5 minutes — `AgentService.java:300-313`.

### 3.5 Notification Dispatch

```mermaid
sequenceDiagram
  participant CRON as Scheduler 0 0 8 * * *
  participant CES as CertificateExpiryScheduler
  participant CR as CertRepo
  participant NS as NotificationService
  participant MAIL as JavaMailSender
  CRON->>CES: checkExpiringCertificates
  loop per Organization
    CES->>CR: findExpiringByOrgId(now, now+warn)
    loop per CertificateRecord
      CES->>NS: dispatchExpiryAlert(target, daysLeft, severity) [@Async]
      NS->>NS: read target.notificationChannels.email
      NS->>MAIL: send (skip if devMode)
    end
  end
```

Anchors: `CertificateExpiryScheduler.java:46-93`, `NotificationService.java:47-101`, `application.yml:76-79`.

Agent-offline alerts follow the same shape via `AgentOfflineScheduler.java:53-123`.

## 4. Data Model Overview

Entities (all extend `BaseEntity` with UUID PK + `created_at`/`updated_at`, `BaseEntity.java:12-25`):

- `Organization` (org hierarchy via `parentOrg`, `orgType` SINGLE|MSP) — `Organization.java`
- `User` (unique `email`, optional `google_sub`, legacy `role` enum) — `User.java`
- `OrgMember` (user ↔ org N:M with `OrgMemberRole` ADMIN|ENGINEER|VIEWER) — `V5__…sql:53-70`
- `Invitation` (token_hash, email OTP flow) — `Invitation.java`
- `Subscription` (1:1 org, `max_certificate_quota`) — `V4__…sql`
- `Location` (org-scoped, cloud/on-prem tagging) — `V5__…sql:27-44`
- `Target` (org, optional agent, optional location, `is_private`, JSONB `notification_channels`, JSONB `tags`) — `Target.java`
- `Agent` (org, hashed key, mTLS cert PEM, JSONB `allowed_cidrs`, quota) — `Agent.java`
- `AgentRegistrationToken` (one-time, expiring) — `V3__…sql:32-43`
- `AgentScanJob` (agent ↔ target, status PENDING|CLAIMED|COMPLETED|FAILED) — `AgentScanJob.java`
- `CertificateRecord` (target-scoped, issuer, serial, expiry, status, key info, SANs) — `CertificateRecord.java`

## 5. Deployment Topology

```mermaid
graph LR
  subgraph host[Docker host]
    nginx[nginx:alpine :80/:443]
    app[certguard-app :8443 HTTPS]
    pg[(postgres:15-alpine :5432)]
    rmq[rabbitmq:3.12-management :5672/:15672]
    prom[prometheus :9090]
    graf[grafana :3000]
  end
  nginx --> app --> pg
  app --> rmq
  prom --> app
  graf --> prom
  vol1[(postgres_data)] --- pg
  vol2[(rabbitmq_data)] --- rmq
  vol3[(prometheus_data)] --- prom
  vol4[(grafana_data)] --- graf
  certs[./certs ro] --- app
  certs --- nginx
```

Anchors: `docker-compose.yml` — network `certguard-net` (bridge, line 172), volumes (161-166), all service ports bound to 127.0.0.1 except nginx :80/:443. App port 8443 is HTTPS with PKCS12 keystore at `/opt/certguard/certs/certguard.p12` (`application.yml:120-127`).

## 6. Non-Functional Concerns

### Security
- TLS: server 8443 TLS 1.2/1.3 via PKCS12 (`application.yml:120-127`); agent pins server cert by SHA-256 fingerprint, else trust-all in dev (`SecureHttpClient.java:109-138`).
- AuthN: JWT HS-signed with 64-char secret (`JwtTokenProvider.java`), JWT in `Authorization: Bearer`; agent auth via `X-Agent-Id` + `X-Agent-Key` BCrypt-verified (`AgentAuthFilter.java:57-109`) plus per-result HMAC-SHA256 (`AgentHmacService.java`).
- AuthZ: `@PreAuthorize("hasRole(...)")` on team/MSP/admin endpoints (`TeamController.java:34`, `OrgController.java:50`, `MspClientController.java:27`). All `/api/v1/**` require auth (`SecurityConfig.java:71`).
- Secrets: BCrypt for token & agent-key hashes; invite tokens stored as SHA-256 hash (`Invitation.java:27-29`).
- Agent CA: self-managed root CA (RSA-4096, 10y) generated lazily to `/opt/certguard/certs/agent-ca*.pem`, signing RSA-2048 client certs 365 days (`AgentCertificateAuthority.java:73-151`, `application.yml:134-139`).

### Multi-tenancy
- ThreadLocal `TenantContext.orgId` set from JWT `orgId` claim (`JwtAuthenticationFilter.java:47-48`), used by every service call.
- Denormalized `org_id` on `certificate_records` and `agent_scan_jobs` for read-side filtering.
- MSP hierarchy via `organizations.parent_org_id` + `MspClientController`.

### Scalability
- HikariCP 20 max (`application.yml:15-17`). Direct scans use 20-thread pool. Async via `@EnableAsync` with 4/10/50 pool (`application.yml:68-71`).
- Agent pull-based model — server does not need to reach inside customer networks.
- RabbitMQ infra running but no `@RabbitListener` or `RabbitTemplate` usage found in code read (phase-3 placeholder).

### Observability
- Actuator `health,info,prometheus` exposed (`application.yml:94-98`), Prometheus + Grafana in compose.
- Logging via Logback; Spring Security at DEBUG (noisy for prod).

### Reliability
- Flyway `repair()` on startup and `validateOnMigrate(false)` (`FlywayConfig.java:13-23`) — risk of accepting checksum drift.
- Stale-job reclaim at 5-min cadence (`AgentService.resetStaleClaimedJobs`).
- Agent offline detection at 5-min cadence, threshold 10 min (`AgentOfflineScheduler`).
- Retry logic on direct scan (3 attempts with linear backoff, `SslScannerService.java:71-81`).

## 7. Open Questions / Risks

1. **Agent mTLS not actually enforced on server.** Server issues client certs, but `AgentAuthFilter` only verifies `X-Agent-Id`/`X-Agent-Key` bearer-style; there is no `sslClientAuth` / client-cert check in `SecurityConfig` or Tomcat connector config. Client cert on the agent is loaded as a cert-only entry with `kmf.init(ks, null)` and no private key (`SecureHttpClient.java:92-98`), so the TLS mutual handshake cannot actually succeed with a private key. The mTLS claim is effectively symbolic.
2. **CORS `allowedOriginPatterns=*` with `allowCredentials=true`** (`SecurityConfig.java:90-94`) — permissive for browser JWT flows.
3. **Direct scanner uses trust-all `X509TrustManager`** (`SslScannerService.java:83-88`) — correct for inventory but must not be reused for any authenticated outbound call.
4. **Flyway `repair()` on every boot + `validateOnMigrate(false)`** — silences migration drift; risks prod drift going undetected.
5. **RabbitMQ infra provisioned but unused by code** — operational cost with no functional benefit; decide to adopt for scan-job dispatch or remove.
6. **OTP store is in-memory `ConcurrentHashMap`** (`InvitationService.java:55-57`) — incompatible with multi-instance deployment; noted as "should be Redis".
7. **Dev fallbacks dangerous if leaked to prod**: `APP_DEV_MODE=true` default bypasses OAuth, enables `/api/v1/auth/dev-token` (`DevAuthController.java:48-86`), and short-circuits email sends.
8. **`agent_scan_jobs` claim has no locking** — `findPendingJobsForAgent` + save in `AgentService.pollJobs` (`AgentService.java:136-144`) is subject to double-claim under concurrent agents (only one per org realistically, but worth `SELECT … FOR UPDATE SKIP LOCKED`).
9. **JWT long-lived (24h), no refresh, no revocation list** (`application.yml:66`). Revoking an agent's key works (status check in filter) but revoking a user JWT does not.
10. **No pagination index strategy** documented for `certificate_records` beyond expiry/status/target/org indexes (`V1`).
