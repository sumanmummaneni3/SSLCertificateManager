# CertGuard — Low-Level Design

## 1. Server — Module Class Map

### 1.1 Bootstrap & Config
- `com.certguard.CertGuardApplication` — `@EnableAsync @EnableScheduling` entry point. `CertGuardApplication.java:8-15`.
- `config.SecurityConfig` — filter chain, CORS, OAuth2 enablement gated on `devMode`. `SecurityConfig.java:46-98`.
- `config.FlywayConfig` — custom Flyway bean invoking `repair()` with `validateOnMigrate(false)`. `FlywayConfig.java:10-25`.
- `config.WebMvcConfig`, `config.PasswordEncoderConfig`, `config.JsonListConverter` (JSONB ↔ List converter).
- `config.OAuth2AuthenticationSuccessHandler` — provisions User+Org+Subscription on first Google login; mints JWT and redirects. `OAuth2AuthenticationSuccessHandler.java:45-138`.

### 1.2 Security
- `security.JwtTokenProvider` — HS signing, claims `{sub,orgId,email,role}`. `JwtTokenProvider.java:29-65`.
- `security.JwtAuthenticationFilter` — extracts Bearer, seeds `TenantContext` + `SecurityContextHolder`. `JwtAuthenticationFilter.java:28-63`.
- `security.AgentAuthFilter` — `X-Agent-Id`+`X-Agent-Key` BCrypt check; sets `authenticatedAgent` request attr; updates `last_seen_at`. `AgentAuthFilter.java:40-109`.
- `security.AgentHmacService` — HMAC-SHA256 verify of `targetId:scanType:serial:notAfterMs`. `AgentHmacService.java:19-47`.
- `security.AgentCertificateAuthority` — lazy CA init, `issueClientCertificate(agentId, orgId)` RSA-2048 clientAuth EKU. `AgentCertificateAuthority.java:52-167`.
- `security.TenantContext` — ThreadLocal `orgId`/`userId`. `TenantContext.java:5-14`.
- `security.CertGuardUserPrincipal` — custom principal exposing `getUserId`, `getAuthorities`.

### 1.3 Controllers
(see endpoint table in §2)

- `AgentController` — admin + public + agent-authenticated endpoints. `AgentController.java`
- `TargetController` — `TargetController.java`
- `CertificateController` — list, expiring, dashboard. `CertificateController.java:19-32`
- `TeamController`, `MspClientController`, `OrgController`, `LocationController`, `DevAuthController`, `AgentDownloadController`, `SpaController`.

### 1.4 Services
- `TargetService` — quota, CIDR validation, dedup, agent assignment, scan dispatch, scan-status. `TargetService.java:42-285`.
- `AgentService` — token mint, register (issues client cert & plain agent key — last chance to read), heartbeat, poll, HMAC-verified result processing (FULL/DELTA), queueScanJob, revoke, `resetStaleClaimedJobs`, `cleanupExpiredTokens`. `AgentService.java:46-364`.
- `SslScannerService` — direct scan with trust-all SSLContext + SNI; retry×3; nightly cron. `SslScannerService.java:43-158`.
- `CertificateService`, `CertificateExpiryScheduler`, `AgentOfflineScheduler`, `NotificationService`, `InvitationService`, `TeamService`, `LocationService`, `OrgService`, `MspClientService`, `OAuth2UserService`.

### 1.5 Repositories
All under `com.certguard.repository.*`; `Spring Data JpaRepository<Entity, UUID>` with org-scoped finders, e.g.:
- `AgentScanJobRepository.findPendingJobsForAgent`, `findStaleClaimedJobs`, `existsByTargetIdAndStatusIn` — used in `AgentService.java:137,241,265,304`.
- `TargetRepository.findAllByOrganizationId`, `existsByOrganizationIdAndHostAndPort`, `findByIdAndOrganizationId`, `findAllByIsPrivateFalseAndEnabledTrue` — used across `TargetService` & `SslScannerService`.
- `CertificateRecordRepository.findByTargetIdAndSerialNumber`, `findTopByTargetIdOrderByScannedAtDesc`, `findExpiringByOrgId`, `findAllByTargetId`.

### 1.6 Exceptions
- `exception.GlobalExceptionHandler` — `@RestControllerAdvice` mapping each exception to `ProblemDetail`. `GlobalExceptionHandler.java:11-50`.
- `ResourceNotFoundException`, `QuotaExceededException`.

## 2. REST Endpoint Table

Auth legend: **J** = JWT Bearer (`JwtAuthenticationFilter`); **A** = Agent key headers (`AgentAuthFilter`); **P** = Public; **OAuth** = Spring OAuth2 filter.

| Method | Path | Auth | Request | Response | Notable errors |
|---|---|---|---|---|---|
| GET | `/oauth2/authorization/google` | OAuth | — | 302 → Google | — |
| GET | `/login/oauth2/code/google` | OAuth | code | 302 → `{baseUrl}/?token=…` | 401 no email |
| GET | `/api/v1/auth/config` | P | — | `{devMode}` | — |
| POST | `/api/v1/auth/logout` | P | — | `{message}` | — |
| POST | `/api/v1/auth/dev-token` | P (dev-mode) | `email,role` | `{token,orgId,...}` | 403 if not dev, 400 role |
| POST | `/api/v1/auth/invite/validate` | P | `token` (query) | `{email,message}` | 400 invalid/expired |
| POST | `/api/v1/auth/invite/accept` | P | `AcceptInviteRequest{token,email,otp}` | `{token,orgId,email,role}` | 400 OTP mismatch |
| GET | `/api/v1/dashboard` | J | — | `DashboardResponse` | 401 |
| GET | `/api/v1/certificates` | J | `Pageable` | `Page<CertificateResponse>` | 401 |
| GET | `/api/v1/certificates/expiring?days=30` | J | — | `List<CertificateResponse>` | — |
| GET | `/api/v1/targets` | J | `Pageable` | `Page<TargetResponse>` | — |
| POST | `/api/v1/targets` | J | `CreateTargetRequest` | 201 `TargetResponse` | 400 dup/CIDR, 429 quota |
| PUT | `/api/v1/targets/{id}` | J | `UpdateTargetRequest` | `TargetResponse` | 404, 400 |
| DELETE | `/api/v1/targets/{id}` | J | — | 204 | 404 |
| POST | `/api/v1/targets/{id}/scan` | J | — | `{message}` | 409 no agent, 404 |
| GET | `/api/v1/targets/{id}/scan-status` | J | — | `ScanStatusResponse` or null | 404 |
| GET | `/api/v1/targets/{id}/notifications` | J | — | `Map` | 404 |
| PUT | `/api/v1/targets/{id}/notifications` | J | `Map<String,Object>` | `TargetResponse` | — |
| GET | `/api/v1/locations` | J | — | `List<LocationResponse>` | — |
| POST/PUT/DELETE | `/api/v1/locations/{id?}` | J | `CreateLocationRequest` | LocationResponse / 204 | 404 |
| GET/PUT | `/api/v1/org`, `/api/v1/org/profile`, `/api/v1/org/name` | J | `UpdateOrgProfileRequest` | `OrgResponse` | — |
| GET | `/api/v1/org/admin/orgs` | J + `ROLE_PLATFORM_ADMIN` | — | `List<OrgResponse>` | 403 |
| PUT | `/api/v1/org/admin/orgs/{orgId}/quota?value=N` | J + PLATFORM_ADMIN | — | `OrgResponse` | 403, 404 |
| GET | `/api/v1/org/members` | J | — | `List<OrgMemberResponse>` | — |
| POST | `/api/v1/org/invitations` | J + ADMIN/PLATFORM_ADMIN | `InviteMemberRequest` | 201 `InvitationResponse` | 403 |
| PUT | `/api/v1/org/members/{userId}/role?role=` | J + ADMIN/PLATFORM_ADMIN | — | `OrgMemberResponse` | 403, 404 |
| DELETE | `/api/v1/org/members/{userId}` | J + ADMIN/PLATFORM_ADMIN | — | 204 | 403, 404 |
| GET/POST/PUT | `/api/v1/msp/clients[/{id}]` | J + role | `CreateClientOrgRequest` | `OrgResponse` | 403 |
| POST | `/api/v1/agent/tokens?agentName=` | J | — | 201 `RegistrationTokenResponse` | 404 org |
| GET | `/api/v1/agent/config?agentName=&token=` | J | — | text/plain properties file | — |
| GET | `/api/v1/agent/list` | J | — | `List<AgentResponse>` | — |
| POST | `/api/v1/agent/{agentId}/revoke` | J | — | 204 | 404 |
| GET | `/api/v1/agent/ca-cert` | P | — | `{caCertPem}` | — |
| POST | `/api/v1/agent/register` | P + `X-Org-Id` | `AgentRegisterRequest` | 201 `AgentResponse` (one-time `agentKey`+`clientCertPem`) | 403 token invalid |
| POST | `/api/v1/agent/heartbeat` | A | — | `{status,agentId,serverTime}` | 401 |
| GET | `/api/v1/agent/jobs` | A | — | `List<ScanJobResponse>` (marks CLAIMED) | 401 |
| POST | `/api/v1/agent/results` | A + HMAC | `AgentScanResultRequest` | 200 | 403 HMAC fail, 404 job/target |
| GET | `/agent/download` | P | — | `certguard-agent.jar` (octet-stream) | 404 missing |
| GET | `/agent/version` | P | — | `{version,available,minServerVersion}` | — |
| GET | `/actuator/health,info,prometheus` | P/partial | — | Spring actuator | — |

Anchors: `SecurityConfig.java:56-73`, `AgentController.java:38-157`, `TargetController.java:30-77`, `CertificateController.java:19-32`, `TeamController.java:28-57`, `OrgController.java:25-59`, `MspClientController.java:27-53`, `LocationController.java:23-49`, `DevAuthController.java:38-122`, `AgentDownloadController.java:21-45`.

## 3. Database Schema

```mermaid
erDiagram
  organizations ||--o{ users : has
  organizations ||--|| subscriptions : has
  organizations ||--o{ targets : owns
  organizations ||--o{ agents : owns
  organizations ||--o{ locations : owns
  organizations ||--o{ org_members : has
  organizations ||--o{ invitations : has
  organizations ||--o{ organizations : "parent_org_id"
  users ||--o{ org_members : joins
  users ||--o{ invitations : "invited_by"
  users ||--o{ agent_registration_tokens : "created_by"
  agents ||--o{ targets : scans
  agents ||--o{ agent_scan_jobs : processes
  agents ||--o{ certificate_records : "scanned_by_agent_id"
  targets ||--o{ certificate_records : has
  targets ||--o{ agent_scan_jobs : queued_for
  locations ||--o{ targets : groups

  organizations {
    UUID id PK
    VARCHAR name
    VARCHAR slug UK
    org_type org_type
    UUID parent_org_id FK
    VARCHAR address_line1
    VARCHAR city
    VARCHAR country
    VARCHAR contact_email
  }
  users {
    UUID id PK
    UUID org_id FK
    VARCHAR email UK
    user_role role
    VARCHAR google_sub UK
  }
  subscriptions {
    UUID id PK
    UUID org_id FK UK
    INT max_certificate_quota
    subscription_status status
  }
  targets {
    UUID id PK
    UUID org_id FK
    VARCHAR host
    INT port
    host_type host_type
    BOOL is_private
    BOOL enabled
    JSONB tags
    JSONB notification_channels
    UUID agent_id FK
    UUID location_id FK
    TIMESTAMPTZ last_scanned_at
  }
  certificate_records {
    UUID id PK
    UUID target_id FK
    UUID org_id
    VARCHAR common_name
    TEXT issuer
    VARCHAR serial_number
    TIMESTAMPTZ expiry_date
    TIMESTAMPTZ not_before
    TEXT public_cert_b64
    cert_status status
    VARCHAR key_algorithm
    INT key_size
    VARCHAR signature_algorithm
    JSONB subject_alt_names
    INT chain_depth
    UUID scanned_by_agent_id FK
  }
  agents {
    UUID id PK
    UUID org_id FK
    VARCHAR name
    VARCHAR agent_key_hash
    VARCHAR client_cert_fingerprint
    TEXT client_cert_pem
    JSONB allowed_cidrs
    INT max_targets
    INT current_target_count
    agent_status status
    TIMESTAMPTZ last_seen_at
  }
  agent_registration_tokens {
    UUID id PK
    UUID org_id FK
    VARCHAR token_hash UK
    VARCHAR agent_name
    BOOL used
    TIMESTAMPTZ expires_at
    UUID created_by FK
  }
  agent_scan_jobs {
    UUID id PK
    UUID agent_id FK
    UUID target_id FK
    UUID org_id
    scan_job_status status
    VARCHAR result_type
    VARCHAR error_msg
    TIMESTAMPTZ claimed_at
    TIMESTAMPTZ completed_at
  }
  locations {
    UUID id PK
    UUID org_id FK
    VARCHAR name
    location_provider provider
    VARCHAR geo_region
    VARCHAR cloud_region
    JSONB custom_fields
  }
  org_members {
    UUID id PK
    UUID org_id FK
    UUID user_id FK
    org_member_role role
    UUID invited_by FK
    invite_status invite_status
  }
  invitations {
    UUID id PK
    UUID org_id FK
    VARCHAR email
    org_member_role role
    VARCHAR token_hash UK
    UUID invited_by FK
    TIMESTAMPTZ expires_at
    TIMESTAMPTZ used_at
  }
```

Indexes (from `V1`–`V7`): `idx_users_org_id`, `idx_users_email`, `idx_targets_org_id`, `idx_targets_agent_id`, `idx_targets_location_id`, `idx_certs_target_id`, `idx_certs_org_id`, `idx_certs_expiry`, `idx_certs_status`, `idx_agents_org_id`, `idx_agents_status`, `idx_reg_tokens_org_id`, `idx_scan_jobs_agent_id`, `idx_scan_jobs_target_id`, `idx_scan_jobs_status`, `idx_locations_org_id`, `idx_org_members_org_id`, `idx_org_members_user_id`, `idx_invitations_org_id`, `idx_invitations_email`, `idx_orgs_parent`.

Enums (Postgres `CREATE TYPE`): `user_role(ADMIN,MEMBER,VIEWER,PLATFORM_ADMIN)`, `cert_status(VALID,EXPIRING,EXPIRED,UNREACHABLE,UNKNOWN)`, `host_type(DOMAIN,IP,HOSTNAME)`, `subscription_status`, `agent_status(PENDING,ACTIVE,REVOKED,EXPIRED)`, `scan_job_status(PENDING,CLAIMED,COMPLETED,FAILED)`, `org_type(SINGLE,MSP)`, `org_member_role(ADMIN,ENGINEER,VIEWER)`, `invite_status(PENDING,ACCEPTED,REVOKED)`, `location_provider(AWS,AZURE,GCP,COLOCATION,ON_PREM)`.

## 4. Critical-flow Sequence Diagrams

### 4.1 Agent registration

```mermaid
sequenceDiagram
  participant A as AgentMain
  participant RS as RegistrationService
  participant API as ServerApiClient
  participant C as AgentController
  participant AS as AgentService
  participant CA as AgentCertificateAuthority
  participant DB
  A->>RS: register()
  RS->>API: register(token, orgId)
  API->>C: POST /api/v1/agent/register (X-Org-Id)
  C->>AS: register(req, orgId)
  AS->>DB: find+validate AgentRegistrationToken (BCrypt matches)
  AS->>AS: generate plainAgentKey ("AGK-"+2xUUID), BCrypt hash
  AS->>DB: save Agent(status=ACTIVE)
  AS->>CA: issueClientCertificate(agentId, orgId)
  CA-->>AS: PEM
  AS->>DB: update Agent cert fields; mark token used
  AS-->>C: AgentResponse{id, agentKey, clientCertPem}
  C-->>API: 201
  API-->>RS: JsonNode
  RS->>RS: write PEM; persist id+key; clear token
  RS->>AgentConfig: reload()
```

Anchors: `RegistrationService.java:32-78`, `ServerApiClient.java:46-67`, `AgentController.java:116-127`, `AgentService.java:74-127`, `AgentCertificateAuthority.java:122-151`.

### 4.2 Result submission with HMAC

```mermaid
sequenceDiagram
  participant PL as PollLoop
  participant HS as HmacSigner
  participant API as ServerApiClient
  participant AA as AgentAuthFilter
  participant C as AgentController
  participant AS as AgentService
  participant AHS as AgentHmacService
  PL->>HS: sign(result, agentKey)
  HS-->>PL: base64(HMAC-SHA256("target:type:serial:notAfterMs"))
  PL->>API: submitResult(result, hmac)
  API->>AA: POST /api/v1/agent/results (X-Agent-Id/Key)
  AA->>AA: BCrypt.matches(agentKey, agent.key_hash), status=ACTIVE
  AA->>C: setAttribute authenticatedAgent + key
  C->>AS: submitResult(agent, req, agentKey)
  AS->>AHS: verify(agentKey, targetId, scanType, serial, notAfter, sig)
  alt hmacValid
    AS->>AS: processFull or processDelta
    AS->>DB: upsert CertificateRecord; mark job COMPLETED
  else
    AS-->>C: SecurityException → 403 ProblemDetail
  end
```

Anchors: `HmacSigner.java:20-30`, `AgentAuthFilter.java:57-109`, `AgentController.java:148-157`, `AgentService.java:160-237`, `AgentHmacService.java:19-38`.

## 5. Configuration Surface

### Server (env vars / `application.yml`)

| Property | Env | Default | Purpose |
|---|---|---|---|
| `spring.datasource.url/username/password` | `SPRING_DATASOURCE_*` | `jdbc:postgresql://localhost:5432/certguard` | DB |
| `spring.rabbitmq.*` | `RABBITMQ_*` | rabbitmq:5672 | AMQP (unused) |
| `spring.security.oauth2.client.registration.google.*` | `GOOGLE_CLIENT_ID/SECRET` | `mock-*` | OIDC |
| `spring.mail.*` | `MAIL_HOST/PORT/USERNAME/PASSWORD/FROM` | Gmail 587 | SMTP |
| `app.dev-mode` | `APP_DEV_MODE` | `true` | disables OAuth, enables dev-token |
| `app.jwt.secret` | `JWT_SECRET` | 64-char dev | HS key |
| `app.jwt.expiration-ms` | — | 86400000 (24h) | token TTL |
| `app.base-url` | `APP_BASE_URL` | `http://localhost:8080` | OAuth redirect base |
| `app.platform-admin.emails` | `PLATFORM_ADMIN_EMAILS` | `""` | allowlist |
| `app.alert.warning-days` / `critical-days` / `schedule-cron` | `ALERT_*` | 30 / 7 / `0 0 8 * * *` | expiry alerts |
| `app.scanning.public.*` | — | pool 20, timeout 10s/15s, 3 retries, cron `0 0 2 * * *` | direct scan |
| `app.agent.offline-threshold-minutes` | `AGENT_OFFLINE_THRESHOLD_MINUTES` | 10 | agent watchdog |
| `app.agent.offline-check-interval-ms` | `AGENT_OFFLINE_CHECK_INTERVAL_MS` | 300000 | watchdog cadence |
| `app.agent.cert.validity-days` / `server.agent.ca.*` | `AGENT_CA_CERT_PATH/KEY_PATH` | `/opt/certguard/certs/agent-ca*.pem`, 365d | agent CA |
| `server.port` / `server.ssl.*` | `SSL_KEYSTORE_PASSWORD` | 8443 + PKCS12 | HTTPS |

### Agent (`application.properties`)

| Key | Purpose |
|---|---|
| `certguard.server.url` | Server base URL |
| `certguard.server.cert-fingerprint` | SHA-256 pin; if blank + `trust-self-signed=true`, trust-all |
| `certguard.server.trust-self-signed` | Dev escape |
| `certguard.registration.token` / `.org-id` | One-time bootstrap |
| `certguard.agent.id` / `.key` | Persisted after registration (written by `AgentConfig.set`, `AgentConfig.java:81-95`) |
| `certguard.agent.name` | Display name |
| `certguard.agent.cert-path` | Path for client cert PEM |
| `certguard.agent.allowed-cidrs` | CSV CIDRs |
| `certguard.agent.max-targets` / `poll-interval-seconds` / `scan-timeout-seconds` / `scan-threads` | Tuning |

### Secrets
- `JWT_SECRET` (server), `agent_key_hash` (DB, BCrypt), `token_hash` (BCrypt), invitation `token_hash` (SHA-256).
- `/opt/certguard/certs/certguard.p12` (TLS), `agent-ca-key.pem` (chmod 600 attempted, `AgentCertificateAuthority.java:99-104`).

## 6. Error Model (RFC 9457)

`GlobalExceptionHandler` maps (`GlobalExceptionHandler.java:14-49`):

| Exception | HTTP | Shape |
|---|---|---|
| `ResourceNotFoundException` | 404 | `ProblemDetail` |
| `QuotaExceededException` | 429 | `ProblemDetail` |
| `IllegalArgumentException` | 400 | `ProblemDetail` |
| `IllegalStateException` | 409 | `ProblemDetail` |
| `SecurityException` | 403 | `ProblemDetail` (logged warn) |
| `NoResourceFoundException` | 404 | `ProblemDetail` |
| `Exception` | 500 | Generic "unexpected" |

Notes: not all controllers consistently flow through — e.g., `AgentAuthFilter` writes a raw JSON `{"error":...}` with 401 (`AgentAuthFilter.java:112-117`); `DevAuthController` returns ad-hoc `Map.of("error", …)` with 400/403 rather than `ProblemDetail`. Recommend unification.

## 7. Agent ↔ Server Protocol

- **Transport**: HTTPS (TLS 1.3 only from the agent side, `SecureHttpClient.java:74`), server accepts TLS 1.2/1.3 (`application.yml:127`).
- **Server trust**: SHA-256 fingerprint pin on the agent; if blank and `trust-self-signed=true`, verification is skipped — no JVM truststore chain check. Hostname verification is disabled (`NoopHostnameVerifier`, `SecureHttpClient.java:54-57`).
- **Client auth**: `X-Agent-Id: <uuid>` + `X-Agent-Key: <plain>`. Server BCrypt-verifies key against `agents.agent_key_hash` and checks `status=ACTIVE` (`AgentAuthFilter.java:74-89`). Despite issuance of a mTLS client certificate, no TLS-layer client-cert enforcement exists in `SecurityConfig` or Tomcat config; the agent also loads the cert without its private key (`SecureHttpClient.java:79-107`).
- **Integrity on scan results**: HMAC-SHA256 over `targetId ":" scanType ":" serialNumber ":" notAfter.toEpochMilli()` keyed by the plain agent key (`HmacSigner.java:20-30`, `AgentHmacService.java:30-38`). Constant-time compare (`AgentHmacService.java:40-48`).
- **Endpoints**:
  - `POST /api/v1/agent/register` → `AgentRegisterRequest{registrationToken, agentName, allowedCidrs[], maxTargets, agentVersion}` + `X-Org-Id`. Returns `AgentResponse` including one-time `agentKey` and `clientCertPem`.
  - `POST /api/v1/agent/heartbeat` → `{status,agentId,serverTime}`.
  - `GET /api/v1/agent/jobs` → `[{jobId, targetId, host, port, lastKnownSerialHash, lastCertificateId}]`; side-effect CLAIMED.
  - `POST /api/v1/agent/results` → `AgentScanResultRequest` (FULL or DELTA discriminator via `scanType`). FULL carries full cert attrs; DELTA carries `certificateId` + new `notAfter` only. (`AgentScanResultRequest.java`.)
- **Heartbeat cadence**: `pollIntervalSeconds` (default 30s, `AgentConfig.java:127`). `AgentAuthFilter` also updates `last_seen_at` on every authenticated request (`AgentAuthFilter.java:92-97`).
- **Server-side offline detection**: `AgentOfflineScheduler.checkOfflineAgents` every `offline-check-interval-ms` (default 5 min); flags agents whose `last_seen_at` < now − `offline-threshold-minutes` (default 10 min) and emails `organizations.contact_email` (`AgentOfflineScheduler.java:53-123`).
- **Retry/backoff**: agent has no explicit retry — each `tick()` catches and logs (`PollLoop.java:63-103`). Server recovers stuck CLAIMED jobs via `AgentService.resetStaleClaimedJobs` (10-min threshold, 5-min cadence, `AgentService.java:300-313`).
- **Quota**: `certguard.agent.max-targets` caps jobs processed per tick (`PollLoop.java:80-84`); server-side `agents.max_targets`/`current_target_count` enforced at target creation.
