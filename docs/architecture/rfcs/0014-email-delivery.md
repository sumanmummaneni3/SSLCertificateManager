# RFC 0014 — Email/Notification Delivery

**Status**: DRAFT — Track C implemented; Tracks A and B open, with Track A gated on Open Questions §2 (DNS/SPF control) and §4 (volume sizing). Do not treat Track A/B recommendations as ratified.
**Author**: architect, assessment dated 2026-07-19. Landed to disk by backend-eng (architect has no Write tool); status addendum by architect via team-lead, same date.
**Relates to**: RFC 0008 (expiry notification convergence) — this RFC's Track C is the durable-delivery mechanism RFC 0008's dedup design depends on but never had.

---

## TL;DR

Certificate expiry/revocation alert emails have three independent, stacked problems: (a) `APP_DEV_MODE=true` suppresses every send, (b) the configured hosted relay's credentials are stale/unverified, and (c) failures were silent — no retry, no persistence, no metric — so an SMTP outage silently dropped alerts, sometimes permanently. This RFC proposes three tracks to fix delivery end-to-end: **Track A** (restore or replace the SMTP relay — the only thing that makes mail leave the box), **Track B** (an `EmailSender` transport SPI replacing the direct `JavaMailSender` dependency and the scattered dev-mode branches), and **Track C** (a durable `notification_outbox` table so a transport outage becomes a bounded delay, not data loss).

**As of the status addendum (same day):** Track C is implemented and merged (uncommitted, working tree) — see the Status Addendum section below for what actually shipped, including a rollback bug found and fixed during live testing, and the 34 pre-existing test-infrastructure errors (`ObjectMapper`) it exposed. Track A and Track B remain open. Track A is blocked on the question in Open Questions §1.

---

## 1. Current State (confirmed from source at time of writing)

`NotificationService` (`server/src/main/java/com/certguard/service/NotificationService.java`) injected Spring's `JavaMailSender` directly — no abstraction. The bean comes from `MailSenderAutoConfiguration` via `spring.mail.*` (`application.yml`). Templates are Thymeleaf, HTML + `.txt` multipart, resolved as `email/<name>` and `email/<name>.txt`.

Notification types — expiry warning/critical, revocation, agent-offline, renewal ready/installed/failed, org-migration forward/reverse, pool-starvation — all flow through a single `sendMimeEmail` helper.

Three distinct delivery blockers, stacked:

**(a) `APP_DEV_MODE=true` suppressed every send.** Each dispatch method short-circuited to a log line.

**(b) A hosted relay was already configured** (`eu-west-2.cp-mta.com:587`, sender `no-reply@oopsssl.co.uk`) but with credentials the `.env` itself flagged as stale ("the relay account uses an opaque credential (`smtp_user_*`), not an address"). Source of truth: `/opt/certguard/.env` on the VPS.
**[RESOLVED in the addendum — relay confirmed DEAD by the user.]**

**(c) Failures were silent.** `sendMimeEmail` wrapped everything in `try/catch(Exception) → log.error → return`. No rethrow, retry, persistence, or metric. `management.health.mail.enabled=false`, so the health endpoint would not surface a broken relay.

### The correctness bug (R19)

`ExpiryEvaluationService.evaluateSingle` stamped `last_alert_sent_at` synchronously in-transaction, then registered an `AFTER_COMMIT` hook calling `@Async dispatchExpiryAlert`. The stamp is durable; the send is fire-and-forget. On SMTP failure the dedup gate (default 23h) suppressed retry — the alert was silently lost for a day. Revocation alerts are transition-gated: stamped once, never re-attempted — a failed revocation email was lost **permanently**. This compounds R16 (a revoked cert reported VALID in production).

### Hard constraint on local-SMTP options

`MailConfig` threw `IllegalStateException` at startup if `spring.mail.username`/`password` were blank while `dev-mode=false`; `application.yml` hardcoded `smtp.auth=true` and `starttls.enable=true` — a no-auth local listener could not be used without editing both.

---

## 2. Options — real delivery vs capture-only

| # | Option | Real inbox delivery? | Verdict |
|---|--------|---|---|
| 1 | Embedded SMTP in-process (GreenMail, SubEthaSMTP) | No — capture only | Test-scope only. Reject for prod. |
| 2 | Mail catcher container (MailHog/Mailpit/MailDev) | No — capture only | Useful for dev/QA; zero production progress. |
| 3 | Local MTA on VPS (Postfix/exim) relaying to internet | Technically yes, practically no | Reject — see below. |
| 4 | Hosted relay free tier (SES/SendGrid/Mailgun/Brevo/Resend) | Yes | The correct path. |
| 5 | Outbox/queue table + deferred send | Yes, once a transport exists | Not a transport — the durability layer; needed regardless. |

**Why option 3 is a trap:** most VPS providers block outbound port 25; a fresh IP has zero sender reputation; SPF/DKIM/DMARC on the sending domain plus PTR/rDNS are required; it inherits bounce handling, feedback loops, and blocklist remediation. For certificate expiry alerts — mail whose entire value is a human reliably reading it — spam-foldering is total failure. A self-run MTA is a multi-week reputation project, not a workaround.

**Critical distinction:** if the goal is "users actually receive expiry alerts," options 1–3 do not achieve it. Only a hosted relay does.

---

## 3. Decision — three tracks

### Track A — unblock now: fix the transport

Provision/repair a hosted relay (Brevo ~300/day, Resend ~3k/mo, SES ~$0.10/1k — all ample for this workload). Set `MAIL_*` and `APP_DEV_MODE=false`; recreate the container with `--force-recreate` (`restart` does not re-read `.env`); verify.

**Status: STILL OPEN.** Blocked on Open Questions §1 and §2 below (relay liveness — answered — and DNS/SPF/DKIM control — not yet).

### Track B — transport SPI (survives the relay swap)

```
com.certguard.notification.transport.EmailSender (interface: send(EmailMessage) throws)
  ├─ SmtpEmailSender    @ConditionalOnProperty(app.mail.transport=smtp, matchIfMissing=true)
  ├─ LoggingEmailSender @ConditionalOnProperty(app.mail.transport=log)  ← replaces devMode if-blocks
  └─ (future) ApiEmailSender for SES/Resend HTTP API — no SMTP egress needed
```

`NotificationService` would depend on `EmailSender`, not `JavaMailSender`. Swapping relays becomes an env var; adding an HTTP-API provider becomes one new `@Component`. This also deletes the eight scattered `if (devMode)` branches — dev-mode becomes `transport=log`.

**Status: STILL OPEN / DEFERRED.** Not part of the work implemented same-day (see addendum).

### Track C — outbox (fixes R19)

`notification_outbox` table (Flyway-owned); send-intent inserted in the **same transaction** as the dedup stamp. `NotificationOutboxScheduler` drains PENDING rows with exponential backoff under `@SchedulerLock`; at-least-once, durable across restarts; queryable audit and a natural place for a future "resend" admin action. With the outbox in place, `transport=log` becomes a safe temporary workaround even before Track A lands: alerts accumulate durably and drain once the relay is fixed.

**Status: IMPLEMENTED — see Status Addendum.**

---

## 4. Impact analysis (as assessed)

**Modified:** `NotificationService`, `MailConfig` (credential validation conditional on `transport=smtp`), `ExpiryEvaluationService` (outbox insert replaces the `AFTER_COMMIT` dispatch), `application.yml` (`app.mail.transport`; make `smtp.auth`/`starttls` configurable), `docker-compose` (`APP_MAIL_TRANSPORT`).

**New:** `transport` package, `NotificationOutbox` entity + repo, `NotificationOutboxScheduler`, one Flyway migration.

**Docker Compose:** optional `mailpit` service, profile-gated to dev, bound to `127.0.0.1`.

**Multi-tenancy:** the outbox carries denormalized `org_id`; any notification-log endpoint is org-scoped under `/api/v1/organizations/{orgId}/...`.

**Gateway:** no impact — mail is outbound-only; never expose a mail-catcher UI through the gateway.

**Compatibility:** additive; default `transport=smtp` preserves existing behavior.

---

## 5. Risks / GAPS entries proposed

- **R19 (HIGH):** failed alert emails silently lost (stamp-before-send + swallowed exceptions); revocation alerts lost forever. **[FIXED — see Status Addendum.]**
- **R20 (MEDIUM):** zero delivery observability — mail health indicator disabled; no send/failure counters despite Prometheus+Grafana being wired. Proposed: `notification_send_total{result}` and an outbox-depth gauge. **[PARTIALLY ADDRESSED — the delivery-status endpoint + UI banner now exist (see addendum); metrics are still absent.]**
- **R21 (MEDIUM):** SMTP password in `server/.env` working tree — rotate as hygiene. **[CORRECTED — `.env` is gitignored and was never committed; no history exposure. Rotation is optional hygiene, not incident response.]**
- **R22 (LOW):** `MailConfig` + the hardcoded `smtp.auth=true` block any no-auth/local transport. **[STILL OPEN.]**
- **D9 (doc drift):** `application.yml`'s `ddl-auto` is `none`, not the `validate` documented in CLAUDE.md. **[STILL OPEN — independently reconfirmed by backend-eng during Task #8's EXPLAIN verification work (this was not an assumption in the original assessment; it was re-derived from the tree during this review pass and matches). That work also surfaced a related, larger finding, tracked separately as task #14: no Testcontainers test in this codebase exercises the real Flyway-applied schema, because every one of them overrides `ddl-auto` to `create-drop`, which drops and recreates the schema from JPA entity mappings immediately after Flyway applies the real migrations — hand-written indexes, non-JPA CHECK constraints, triggers, and FK constraints are never exercised by any test. Filed separately, not folded into this RFC or GAPS, since it is materially larger than a delivery concern and needs its own scoping.]**

---

## 6. Status Addendum (2026-07-19 — same day as the original assessment)

Several findings above were resolved or corrected after the assessment was written. This addendum is authoritative over the body text where the two disagree.

**Implemented same day (uncommitted, working tree):**

1. **Track C outbox**: `V43__notification_outbox.sql`, `NotificationOutbox` entity/repo, `NotificationOutboxService`, `NotificationOutboxScheduler`. Enqueue is in-transaction with the dedup stamp (both expiry and revocation paths). Verified against live Postgres.
2. **Retry policy sized for outages**: `max-attempts=200`, backoff base 60s exponential, clamped at 3600s (~8 days of retries). An uncapped shift would overflow past N=63 — the clamp is load-bearing.
3. **Rollback-only bug found in live testing and fixed**: `NotificationService` is class-level `@Transactional(readOnly=true)`; `sendOutboxEmail` joining `processOne`'s transaction caused `UnexpectedRollbackException` — failure bookkeeping (`attempts`/`last_error`/`next_attempt_at`) was silently rolled back, and the row retried every tick forever without ever backing off or reaching FAILED. Fix: `@Transactional(propagation=NOT_SUPPORTED)` on `sendOutboxEmail`. This annotation is load-bearing; do not remove it without re-reading its javadoc.
4. **Delivery-status endpoint**: `GET /api/v1/organizations/{orgId}/notifications/delivery-status` — "degraded" counts PENDING-with-`attempts>0` **and** FAILED (FAILED-only would report healthy for the first ~8 days of an outage, given the retry policy in point 2). `lastError` is admin-only; the response never contains recipient addresses. Readable by all org members; tenant-isolated.
5. **UI banner** (`EmailDeliveryBanner.jsx` in `AppShell`): polls every few minutes, fails quiet on error/404, session-dismissable, reappears while degraded, clamps `lastError` display length.
6. **Dev-mode false-SENT mitigated** (task #6): rows whose send was suppressed by `app.dev-mode` are stamped with a `DEV_MODE:` marker in `last_error` so a promoted/inspected database can't mistake a dev-mode no-op for a real delivery. Does not leak into the UI banner — `last_error` is only surfaced there for queued-or-failed rows, and dev-mode-suppressed rows are SENT.
7. **Regression coverage for the rollback bug** (task #7, follow-up to point 3): the `NOT_SUPPORTED` annotation that fixes the rollback-only bug in point 3 is a single line, and nothing in CI would have failed if someone deleted it — the bug was caught once, live, and mock-based unit tests structurally cannot catch it (it lives in the transaction boundary between two real Spring beans, not in either class's logic). Added a Testcontainers integration test wiring the real `NotificationOutboxService` + `NotificationService` against a real Postgres, mocking only `JavaMailSender` at the edge; asserts the failure bookkeeping (`attempts`/`last_error`/`next_attempt_at`) genuinely persisted, read back from a fresh transaction. Verified the test actually guards the regression: temporarily removed `NOT_SUPPORTED`, confirmed the test goes red with `UnexpectedRollbackException`, restored it, confirmed green.
8. **Testcontainers repaired** (task #5; `pom.xml`: testcontainers 1.21.3 + surefire `-Dapi.version=1.44` + `forkedProcessExitTimeoutInSeconds=180`); the integration suite now actually runs. This revealed 34 pre-existing `ObjectMapper` bean-resolution errors in 4 test classes, root-caused to Spring Boot 4's migration to Jackson 3 (the classic `com.fasterxml.jackson.databind.ObjectMapper` bean is no longer auto-configured). Fixed by switching those test classes to locally-constructed mappers; fixing the injection error also unmasked and fixed three further latent bugs (a `@Profile("dev")`-gated endpoint the tests didn't activate; two missing validation-exception handlers in `GlobalExceptionHandler` that were turning every `@Valid` rejection into a 500 app-wide; one stale pre-RFC-0012 test assertion). None of this is outbox/notification-specific, but it was the direct, unavoidable consequence of getting the outbox's own integration tests to run at all.
9. **Retention purge** (task #8, follow-up to point 1, same review cycle): `notification_outbox` had no purge path — SENT/FAILED rows were terminal and immortal, and the table grows with orgs × targets × recipients × days. Added a daily, `@SchedulerLock`-guarded, batched purge (SENT retained 30 days, FAILED retained 90 — FAILED rows are forensic evidence of a real delivery failure and are deliberately kept longer, but not indefinitely). A companion partial index (`V44__notification_outbox_delivery_status_index.sql`) plus a matching `AND status <> SENT` predicate on the delivery-status query keeps that UI-polled endpoint from scanning the whole table as SENT rows accumulate — verified with `EXPLAIN` against the real migrated schema (Flyway-applied V1–V44, not the create-drop schema most tests in this repo run against — see the D9 entry and task #14 above), which is what surfaced that test-infrastructure finding. Note: `CREATE INDEX CONCURRENTLY` was attempted for V44 and found to deadlock — reproduced via `pg_stat_activity`, root-caused to this codebase's `FlywayConfig` sharing the app's HikariCP pool rather than using a dedicated connection, so Flyway's own open migration-lock transaction blocks CONCURRENTLY's wait-for-all-transactions requirement forever. Reverted to a plain transactional `CREATE INDEX` (full reasoning and evidence documented inline in the V44 file). The CONCURRENTLY incompatibility is filed separately as task #15 — every future index migration on a large, actively-written table is affected, not just this one.

**Summary: the R19 outbox described as a proposal in §3/§5 above is now fully shipped** — implementation (point 1), a fixed correctness bug found in live testing (point 3), dev-mode correctness (point 6), regression coverage against reintroducing the point-3 bug (point 7), and retention/indexing so the table doesn't grow unbounded (point 9). Where the body text above still describes Track C in proposal language, treat this addendum as authoritative.

**Still open:** replacement hosted relay provisioning (Track A — nothing delivers real mail until this happens); Track B transport SPI; R20 metrics; R22; D9 and task #14 (the Testcontainers schema-validation gap); task #15 (Flyway/CONCURRENTLY); the SPF/DKIM/DNS question below.

---

## Open Questions

1. **Is `eu-west-2.cp-mta.com` (the configured relay) still live, or just holding a stale credential?** This determines whether Track A is "restore the relay" (credential refresh from `/opt/certguard/.env` on the VPS) or "replace the transport" (provision a new relay from scratch). **Answered: DEAD — confirmed by the user.** Track A is therefore "provision a new relay," not "restore."
2. **Is `oopsssl.co.uk` DNS under our control?** SPF/DKIM records are needed for *any* relay to avoid spam-foldering, regardless of which provider is chosen. **Still open.**
3. **Durable at-least-once (outbox) vs best-effort?** **Decided and implemented: outbox (Track C).**
4. **Volume/tenant count for free-tier relay sizing?** Needed to pick between Brevo/Resend/SES's respective free tiers once Track A resumes. **Still open.**
