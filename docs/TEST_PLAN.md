# CertGuard Test Plan

**Version:** 1.0  
**Date:** 2026-04-19  
**Author:** CertGuard Test Engineering  
**Scope:** certguard-server (Spring Boot 4.0.3, Java 17)

---

## 1. Objectives

1. Verify security boundaries: authentication, authorisation (RBAC), and tenant isolation are enforced at every API layer.
2. Confirm correctness of certificate expiry calculations, scan result persistence, and notification trigger logic.
3. Validate data integrity constraints (unique targets per org, FK cascades, quota enforcement).
4. Assert API contract stability (status codes, ProblemDetail shape, pagination schema, validation errors).
5. Establish performance baselines for list-targets and trigger-scan endpoints.

---

## 2. Test Strategy

| Layer | Framework | Doubles | DB |
|---|---|---|---|
| Service unit | JUnit 5 + Mockito | All dependencies mocked | None |
| Repository integration | JUnit 5 + `@DataJpaTest` + Testcontainers | None (real JPA) | PostgreSQL 16 (container) |
| API / RBAC | `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers | None | PostgreSQL 16 (container) |
| API contract (manual / CI) | Postman / Newman | None | Running instance |
| Performance | k6 | None | Running instance |

**Decision — Testcontainers over H2:** The entities use `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` and `JSONB` columns that are Postgres-specific. H2 in PostgreSQL-compatibility mode does not support named enum types or JSONB, making it unsuitable for repository or full-stack tests.

---

## 3. Environments

| Environment | Config | Notes |
|---|---|---|
| Local (dev) | `application-test.yml`, `app.dev-mode=true` | Docker must be running for Testcontainers |
| CI | Same as local; Docker-in-Docker or Testcontainers Cloud | `mvn -q -DskipITs test` |
| Staging | Full stack via docker-compose | Performance and Postman tests run here |

---

## 4. Entry Criteria

- All production code compiles (`mvn compile` passes).
- PostgreSQL 16 image is accessible via Docker daemon (for Testcontainers tests).
- `app.dev-mode=true` is set for API tests (enables `/api/v1/auth/dev-token`).

## 5. Exit Criteria

- Zero test failures.
- Unit test coverage >= 80 % for `TargetService` and `SslScannerService`.
- All RBAC boundary assertions pass (401 without token, tenant isolation 404).
- k6 smoke test: p95 list-targets < 400 ms, error rate < 1 %.

---

## 6. Coverage Goals

| Feature Area | Priority | Test Types |
|---|---|---|
| AuthZ / RBAC boundaries | Critical | API (MockMvc) |
| Tenant isolation | Critical | API (MockMvc) |
| OAuth callback / JWT issuance | High | Unit (JwtTokenProvider), API (dev-token) |
| User onboarding (invite OTP flow) | High | Unit (InvitationService) |
| Target CRUD | High | Unit (TargetService), Repository, API |
| Direct scan (public) | High | Unit (SslScannerService) |
| Agent scan job dispatch | High | Unit (TargetService.triggerScan) |
| Certificate expiry calculation | High | Unit (SslScannerService.determineStatus) |
| Email notification trigger | Medium | Unit (NotificationService) |
| Dashboard queries | Medium | API (MockMvc) |
| Quota enforcement | Medium | Unit (TargetService.enforceTargetQuota) |

---

## 7. Test Case Catalog

### 7.1 OAuth Login / JWT

| ID | Description | Type | Expected |
|---|---|---|---|
| AUTH-01 | `GET /api/v1/auth/config` — no auth required | API | 200, `devMode` field present |
| AUTH-02 | Dev token — valid email + ADMIN role | API | 200, JWT in response body |
| AUTH-03 | Dev token — invalid role string | API | 400 |
| AUTH-04 | Dev token — when `devMode=false` | API | 403 |
| AUTH-05 | Request with expired JWT | API | 401 |
| AUTH-06 | Request with malformed JWT (garbage string) | API | 401 |
| AUTH-07 | Request with valid JWT but tampered signature | API | 401 |

### 7.2 User Onboarding (Invite OTP)

| ID | Description | Type | Expected |
|---|---|---|---|
| INV-01 | Validate invite — valid raw token | Unit | OTP sent, email returned |
| INV-02 | Validate invite — expired/unknown token | Unit | `IllegalArgumentException` |
| INV-03 | Accept invite — correct OTP | Unit | JWT issued, user created |
| INV-04 | Accept invite — wrong OTP | Unit | `IllegalArgumentException` |
| INV-05 | Accept invite — used token (replay) | Unit | `IllegalArgumentException` |

### 7.3 RBAC (Role Boundaries)

| ID | Description | Type | Expected |
|---|---|---|---|
| RBAC-01 | No token → list targets | API | 401 |
| RBAC-02 | No token → create target | API | 401 |
| RBAC-03 | ADMIN → list targets | API | 200 |
| RBAC-04 | ADMIN → create target | API | 201 |
| RBAC-05 | VIEWER → list targets (read allowed) | API | 200 |
| RBAC-06 | VIEWER → create target (write denied when @PreAuthorize added) | API | 403 |
| RBAC-07 | VIEWER → trigger scan on own org target | API | 403 (future) |
| RBAC-08 | PLATFORM_ADMIN → update subscription quota | API | 200 |
| RBAC-09 | MEMBER → update subscription quota | API | 403 |

### 7.4 Target CRUD

| ID | Description | Type | Expected |
|---|---|---|---|
| TGT-01 | Create target — valid public host | Unit / API | 201, target persisted |
| TGT-02 | Create target — duplicate host:port same org | Unit | `IllegalArgumentException` |
| TGT-03 | Create target — quota exceeded | Unit | `QuotaExceededException` |
| TGT-04 | Create target — org not found | Unit | `ResourceNotFoundException` |
| TGT-05 | Create target — missing host (validation) | API | 400 |
| TGT-06 | Create target — port out of range | API | 400 |
| TGT-07 | Create private target — agent not in org | Unit | `ResourceNotFoundException` |
| TGT-08 | Create private target — agent over max-targets | Unit | `QuotaExceededException` |
| TGT-09 | List targets — pagination page 0, size 20 | Repository / API | 200, content array |
| TGT-10 | List targets — other org returns empty | Repository | 0 results |
| TGT-11 | Get target — belongs to org | Repository | found |
| TGT-12 | Get target — belongs to other org | Repository | empty |
| TGT-13 | Delete target — decrements agent target count | Unit | agent.currentTargetCount -- |
| TGT-14 | Delete target — not in org | Unit | `ResourceNotFoundException` |
| TGT-15 | Update target — host/port dedup check | Unit | `IllegalArgumentException` |

### 7.5 Direct Scan (Public)

| ID | Description | Type | Expected |
|---|---|---|---|
| SCAN-01 | triggerScan — public target calls SslScannerService | Unit | `scanTarget()` called |
| SCAN-02 | triggerScan — private target with no agent | Unit | `IllegalStateException` |
| SCAN-03 | triggerScan — private target queues agent job | Unit | `agentService.queueScanJob()` called |
| SCAN-04 | `determineStatus` — cert expires in 60 days | Unit | `VALID` |
| SCAN-05 | `determineStatus` — cert expires in 5 days | Unit | `EXPIRING` |
| SCAN-06 | `determineStatus` — cert already expired | Unit | `EXPIRED` |
| SCAN-07 | scanSingleTarget — all retries fail → marks UNREACHABLE | Unit | UNREACHABLE status set |

### 7.6 Agent Scan

| ID | Description | Type | Expected |
|---|---|---|---|
| AGNT-01 | Agent register — valid token | API | 200, agent created |
| AGNT-02 | Agent register — expired token | API | 400/401 |
| AGNT-03 | Agent poll job — claims pending job | API | 200, job returned |
| AGNT-04 | Agent poll — no pending jobs | API | 200, empty |
| AGNT-05 | Agent result submit — COMPLETED | API | 200, cert persisted |
| AGNT-06 | Agent result submit — FAILED | API | 200, error recorded |
| AGNT-07 | Agent offline scheduler marks stale agents OFFLINE | Unit | status = OFFLINE |

### 7.7 Email Notification

| ID | Description | Type | Expected |
|---|---|---|---|
| NOTIF-01 | Expiry scheduler — cert expiring in warning window | Unit | email sent |
| NOTIF-02 | Expiry scheduler — cert outside warning window | Unit | no email sent |
| NOTIF-03 | Notification channel config stored as JSONB | Repository | JSON round-trips |

### 7.8 Dashboard

| ID | Description | Type | Expected |
|---|---|---|---|
| DASH-01 | `GET /api/v1/org/dashboard` — ADMIN | API | 200, counts present |
| DASH-02 | `GET /api/v1/org/dashboard` — no token | API | 401 |
| DASH-03 | Dashboard data scoped to caller's org | API | other org certs not counted |

---

## 8. Risk Matrix

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Testcontainers Docker unavailable in CI | Medium | High | Use Testcontainers Cloud or `@Disabled` guard |
| H2 dialect drift from Postgres | High | High | All integration tests use Testcontainers Postgres |
| RabbitMQ unavailable in test | Medium | Medium | AMQP auto-config excluded from unit tests; agent tests use MockMvc with a stub |
| TLS configured on server port 8443 | Low | Medium | SpringBootTest uses `RANDOM_PORT` with `server.ssl.enabled=false` in test profile |
| Flaky async behaviour in scan scheduler | Medium | Medium | Use Awaitility for polling assertions; no `Thread.sleep` |

---

## 9. Executable Test Locations

| Test Class | Layer | Location |
|---|---|---|
| `TargetServiceTest` | Unit (Mockito) | `src/test/java/com/certguard/service/` |
| `TargetRepositoryTest` | Repository (Testcontainers) | `src/test/java/com/certguard/repository/` |
| `TargetControllerRbacTest` | API / RBAC (Testcontainers) | `src/test/java/com/certguard/controller/` |
| k6 smoke script | Performance | `docs/perf/k6-api-smoke.js` |
| Postman collection | API contract | `docs/perf/postman-collection.json` |

---

## 10. Run Commands

```bash
# Unit + repository + API tests (skips ITs)
cd certguard-server-source-20260419-0737
mvn -q -DskipITs test

# Newman API contract (requires running server)
newman run docs/perf/postman-collection.json \
  --env-var BASE_URL=https://localhost:8443 \
  --insecure

# k6 smoke (requires running server and a valid JWT)
TOKEN=$(curl -sk -X POST 'https://localhost:8443/api/v1/auth/dev-token?email=perf@certguard.local&role=ADMIN' | jq -r .token)
BASE_URL=https://localhost:8443 JWT_TOKEN=$TOKEN k6 run docs/perf/k6-api-smoke.js
```
