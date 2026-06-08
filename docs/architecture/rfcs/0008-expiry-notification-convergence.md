# RFC 0008 — Expiry-Notification Convergence: `ExpiryEvaluationService`, per-target/org `notification_settings`, post-scan hook, and force-scan notify

- **Status:** Proposed (product owner confirmed all three decisions 2026-06-07)
- **Authors:** CertGuard Architect
- **Relates to:** RFC 0004 (notification deep-links / widened `dispatchExpiryAlert` signature), HLD §3.5, LLD §1.4 / §2 / §5, GAPS N8
- **Supersedes:** the hardcoded app-wide `app.alert.*` threshold model as the source of truth (kept only as last-resort fallback)

## 0. Grounding

Anchored against live source under `server/src/main/java/com/certguard/`:

- Expiry alerting is driven **only** by the daily 08:00 cron in `CertificateExpiryScheduler.checkExpiringCertificates` (`service/CertificateExpiryScheduler.java:58-97`). No scan write-path notifies.
- Two scan write-paths exist and neither calls `NotificationService`:
  - Direct/public: `SslScannerService.persistCertificates` (`service/SslScannerService.java:117-149`), reached by the scheduled public scan (`:45-53`, cron `0 0 2 * * *`) and by manual/force scan (`TargetController.scan` `controller/TargetController.java:58-63` → `TargetService.triggerScan` `service/TargetService.java:199-219` → `SslScannerService.scanTarget` `:55-58`).
  - Agent-reported: `AgentService.processFull` (`service/AgentService.java:208-231`) and `processDelta` (`:233-244`), reached from `submitResult` (`:168-206`).
- Manual/private force scan only queues an agent job (`TargetService.triggerScan:212-218` → `AgentService.queueScanJob:247-273`); the cert isn't written until the agent reports back via `submitResult`.
- The `@Async`+boolean dedup bug is confirmed: `NotificationService.dispatchExpiryAlert` is `@Async` and returns `boolean` (`service/NotificationService.java:93-127`); `@EnableAsync` is active (`CertGuardApplication.java:9`); the caller consumes the return synchronously (`CertificateExpiryScheduler.java:88-92`) so `stampAlertSentAt` (`repository/CertificateRecordRepository.java:63-65`) never runs.
- Thresholds are app-wide `@Value` config (`application.yml:98-105`), read in `CertificateExpiryScheduler` (`:42-44`), `SslScannerService` (`:39-40`), and **hardcoded `30`** in `AgentService.determineStatus` (`:361-366`) — a divergence.
- Settings today: per-target channel JSONB (`Target.notificationChannels`, `PUT /api/v1/targets/{id}/notifications` → `TargetService.updateNotificationChannels:290-298`) with org fallback to `org_notification_channels` (`entity/OrgNotificationChannel.java`, `NotificationService.resolveChannels:244-263`). Thresholds are not tenant-scoped.

## 1. Decisions (from product owner)

1. **Thresholds are overridable per-target**, resolving per-target → org default → app.yml fallback.
2. **Add a daily sweep for private/agent targets**, mirroring the public-scan cron + ShedLock pattern, staggered to avoid flooding agents.
3. **Force/manual scan ALWAYS notifies** (bypasses the per-cert dedup window if the cert is in-window); the scheduled sweep keeps dedup. Mitigate self-spam with a short per-target force-scan debounce reusing `Target.lastScannedAt`.
4. (Cross-cutting) Fix the `@Async`+boolean bug so the force path reliably sends and the scheduled path reliably dedups+stamps.

## 2. `ExpiryEvaluationService` — the single convergence point

New `@Service` `com.certguard.service.ExpiryEvaluationService`. It is the **only** place that resolves thresholds, computes severity, applies the dedup gate, stamps, and triggers dispatch. All scan paths and the scheduler delegate to it; the threshold/severity/status logic is removed from the three current copies.

### 2.1 Trigger mode

```java
enum EvaluationMode { SCHEDULED, FORCE }
```

- `SCHEDULED` — applies the per-cert dedup gate (skip if `last_alert_sent_at` within `dedup_hours`). Used by the daily expiry sweep and the daily private-scan-driven evaluations.
- `FORCE` — **bypasses** the dedup gate (decision #3). Used only by user-initiated scans. Still subject to the **per-target force-scan debounce** (§5) so back-to-back clicks don't each send.

### 2.2 API (service-internal)

```java
// Single cert, called from scan write-paths immediately after the cert row is saved.
void evaluateAndNotify(CertificateRecord cert, EvaluationMode mode);

// Batch, called from the daily expiry sweep.
int evaluateAndNotify(Collection<CertificateRecord> certs, EvaluationMode mode); // returns # dispatched
```

### 2.3 Per-cert algorithm

```
settings = resolveSettings(cert.target)          // §3 resolution chain
if (!settings.enabled) return                     // master kill switch
daysLeft = DAYS.between(now, cert.expiryDate)
if (daysLeft > settings.warningDays) return       // not in-window → no alert, no stamp
severity = (daysLeft <= settings.criticalDays) ? CRITICAL : WARNING

if (mode == SCHEDULED) {
    if (cert.lastAlertSentAt != null && cert.lastAlertSentAt.isAfter(now - settings.dedupHours)) return  // dedup
}
// mode == FORCE: skip the dedup check entirely (debounce already applied upstream, §5)

stampAlertSentAt(cert.id, now)                    // in-transaction, synchronous (always stamp, both modes)
enqueueDispatchAfterCommit(cert, daysLeft, severity)   // §4
```

Notes:
- Stamping in **both** modes keeps `last_alert_sent_at` meaningful as "last time we emailed about this cert," and lets the SCHEDULED path naturally dedup against a recent FORCE alert. FORCE simply doesn't *read* the gate; it still *writes* it.
- The `CertStatus` derivation (`VALID/EXPIRING/EXPIRED`) stays in the scan persisters but **must use the resolved `warningDays`** rather than a hardcoded literal — fixes the `AgentService` `30` divergence (`AgentService.java:364`). Pass the resolved value in, or have the persister call a shared helper on this service.

## 3. `notification_settings` data model + resolution

### 3.1 Schema (additive, validate-safe)

New table. Org-scoped with optional per-target override (decision #1), denormalizing `org_id` per the house convention (`CLAUDE.md`: child tables carry `org_id` alongside the parent FK).

```sql
-- V33__create_notification_settings.sql  (Phase 3 — trigger_source took V32)
CREATE TABLE notification_settings (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    target_id     UUID     NULL REFERENCES targets(id)       ON DELETE CASCADE,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    warning_days  INT     NOT NULL DEFAULT 30,
    critical_days INT     NOT NULL DEFAULT 7,
    dedup_hours   INT     NOT NULL DEFAULT 23,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ns_thresholds   CHECK (critical_days > 0 AND warning_days > critical_days AND dedup_hours >= 1)
);

-- Exactly one org-default row (target_id IS NULL) per org, and at most one override per target.
CREATE UNIQUE INDEX uq_notification_settings_org_default
    ON notification_settings(org_id) WHERE target_id IS NULL;
CREATE UNIQUE INDEX uq_notification_settings_target
    ON notification_settings(target_id) WHERE target_id IS NOT NULL;

CREATE INDEX idx_notification_settings_org_id ON notification_settings(org_id);
```

- Two partial unique indexes enforce "one org default + at most one per-target override" without a composite-null pitfall (Postgres treats NULLs as distinct in a plain `UNIQUE(org_id, target_id)`).
- `updated_at` auto-trigger: follow whatever trigger pattern the existing tables use (the codebase auto-manages `created_at`/`updated_at` via DB triggers — reuse the existing trigger function in the same migration).
- **No changes to existing tables** → `ddl-auto: validate` (`application.yml:23-24`) stays green. `last_alert_sent_at` already exists (`CertificateRecord.java:83`).

New entity `NotificationSettings` (`@Entity @Table("notification_settings")` extending `BaseEntity`), with `@ManyToOne` `organization` and nullable `@ManyToOne` `target`. Repository `NotificationSettingsRepository`:

```java
Optional<NotificationSettings> findByTargetId(UUID targetId);
Optional<NotificationSettings> findByOrgIdAndTargetIdIsNull(UUID orgId);
// Batch form for the sweep to avoid N+1:
List<NotificationSettings> findByOrgIdInAndTargetIdIsNull(Collection<UUID> orgIds);
List<NotificationSettings> findByTargetIdIn(Collection<UUID> targetIds);
```

### 3.2 Resolution chain

```
resolveSettings(target):
  perTarget = findByTargetId(target.id)             // override (rare)
  if perTarget present → return perTarget
  orgDefault = findByOrgIdAndTargetIdIsNull(orgId)  // org default
  if orgDefault present → return orgDefault
  return AppDefaults(app.alert.warning-days/critical-days/dedup-hours)  // fallback
```

### 3.3 Sweep N+1 mitigation (the extra-join cost from decision #1)

The daily expiry sweep currently does one `JOIN FETCH` query (`findExpiringWithTargets`, `repository/CertificateRecordRepository.java:42-48`). Adding per-target threshold resolution must not become per-cert lookups. Strategy:

1. Run `findExpiringWithTargets(now, maxWarnCutoff)` where `maxWarnCutoff = now + MAX(warning_days across all settings, default 30)`. Since per-target windows can be **larger** than the global default, widen the fetch window to the **maximum configured `warning_days`** (one cheap `SELECT max(warning_days)` first), then filter precisely in memory per the resolved per-cert window. This keeps the candidate set correct without N+1.
2. Bulk-load settings once: collect candidate `targetId`s and `orgId`s, then two batch queries (`findByTargetIdIn`, `findByOrgIdInAndTargetIdIsNull`), assembled into an in-memory resolver map. No per-cert DB round-trips.

This bounds the sweep at: 1 max-window query + 1 cert+target fetch + 2 settings batch queries, regardless of cert count.

### 3.4 API surface (settings UI)

Reuse the existing (singular) `/api/v1/org/...` and `/api/v1/targets/...` conventions already in the codebase (`OrgController`, `TargetController`). **Flag to backend-engineer:** the codebase uses `/api/v1/org/...` (singular) while CLAUDE.md/HLD specify `/api/v1/organizations/{orgId}/...`; do **not** introduce a third style — match the live `/api/v1/org/...`.

| Method | Path | Auth (role) | Body | Response |
|---|---|---|---|---|
| GET | `/api/v1/org/notification-settings` | ADMIN/ENGINEER/VIEWER/PA | — | `{enabled, warningDays, criticalDays, dedupHours}` (org default; falls back to app defaults if no row) |
| PUT | `/api/v1/org/notification-settings` | ADMIN/PA | `{enabled, warningDays, criticalDays, dedupHours}` | updated org default |
| GET | `/api/v1/targets/{id}/notification-settings` | ADMIN/ENGINEER/VIEWER/PA | — | per-target override, or `{inherited:true, ...effective}` when none |
| PUT | `/api/v1/targets/{id}/notification-settings` | ADMIN/ENGINEER/PA | `{enabled, warningDays, criticalDays, dedupHours}` | created/updated override |
| DELETE | `/api/v1/targets/{id}/notification-settings` | ADMIN/ENGINEER/PA | — | 204 — clears override, reverts to org default |

Channel config endpoints (`/api/v1/targets/{id}/notifications`, and a future `/api/v1/org/notification-channels`) are **unchanged** — channels stay in JSONB; this RFC only adds the **policy** (thresholds + master enable) surface. Validation failures (`critical >= warning`, non-positive) → `IllegalArgumentException` → 400 ProblemDetail (existing `GlobalExceptionHandler` mapping, LLD §6). RBAC mirrors `TargetController` (`@PreAuthorize` write = ADMIN/ENGINEER/PLATFORM_ADMIN; read includes VIEWER).

## 4. Fixing the `@Async`+boolean bug

Root cause: a return value is consumed across an async proxy boundary. Fix structurally, not by re-synchronizing email I/O:

1. **`dispatchExpiryAlert` returns `void`** (drop the `boolean`). The dedup decision and the `stampAlertSentAt` move **out** of the caller and **into** `ExpiryEvaluationService`, executed **synchronously in the caller's transaction**.
2. **Dispatch happens after commit, fire-and-forget.** `ExpiryEvaluationService.enqueueDispatchAfterCommit(...)` registers an `AFTER_COMMIT` action (Spring `@TransactionalEventListener(phase = AFTER_COMMIT)` or `TransactionSynchronizationManager`) that invokes `NotificationService.dispatchExpiryAlert(cert, daysLeft, severity)` (still `@Async` for the SMTP I/O, but now for side-effect only). SMTP failure logs (existing `sendMimeEmail` catch, `NotificationService.java:310-313`) and never rolls back the scan/sweep.
3. This mirrors the proven offline-scheduler pattern (fire-and-forget then stamp unconditionally — LLD §7 / `AgentOfflineScheduler`).

Semantics guarantee: the stamp commits with the scan/sweep transaction (durable dedup state) while email send is decoupled. For FORCE, the stamp still writes (so a later SCHEDULED run dedups against it) but the gate isn't read.

## 5. Force-scan debounce (self-spam mitigation)

Decision #3 removes dedup for FORCE, so repeated clicks could each email. Mitigation that does **not** contradict "force always notifies":

- A **per-target force-scan debounce**: a force scan is only honored as a *fresh* scan (and thus only re-evaluates+notifies) if `target.lastScannedAt` is older than `app.alert.force-scan-debounce-seconds` (new config knob `N`, default **120s**). Within the debounce window, the `POST /scan` returns a `202`-style "scan already in progress / recently completed; showing latest" message and **does not** re-trigger evaluation.
- Reuses the existing `Target.lastScannedAt` column (stamped at `SslScannerService.persistCertificates:142` and `AgentService.submitResult:196`); no new column.
- Config: `app.alert.force-scan-debounce-seconds: ${ALERT_FORCE_SCAN_DEBOUNCE_SECONDS:120}` in `application.yml`.

**Async caveat for private/agent force scans:** for private targets, `POST /scan` only *queues* a job; the cert is written later in `submitResult`. The debounce check therefore must be applied at **evaluation time inside `submitResult`'s evaluate call** (compare prior `lastScannedAt` before it is overwritten), not only at queue time — otherwise the agent round-trip defeats the debounce. Implementation note: capture `previousLastScannedAt` before stamping the new one, and pass `mode=FORCE` only when the job originated from a user trigger. To distinguish user-triggered from sweep-triggered agent jobs, see §6.3.

> **Known limitation (accepted 2026-06-07).** For agent/private targets the debounce baseline (`lastScannedAt`) only advances when a scan *completes*, so the debounce window is effectively a no-op for targets that scan infrequently. Back-to-back force clicks are still collapsed by `queueScanJob`'s PENDING/CLAIMED de-dup, but a user who force-scans, waits for it to complete, then force-scans again within the window **will receive two emails**. This is accepted as-is: the synchronous public path is fully debounced, and "FORCE always notifies" (decision #3) is the intended bias. If repeat-force spam becomes a problem, add a `last_force_requested_at` stamp written at queue time and use it as the debounce baseline for the agent path.

## 6. Daily private-target scan sweep (decision #2)

### 6.1 Where it lives

New scheduled method, co-located with the existing public sweep for symmetry: add `scheduledPrivateScan()` to `SslScannerService` **or** a sibling `ScheduledPrivateScan` component. Recommendation: a **separate `PrivateScanScheduler` `@Component`** (the public scan does direct TLS handshakes; the private sweep only enqueues agent jobs — different concern). It depends on `TargetRepository` + `AgentService`.

```java
@Scheduled(cron = "${app.scanning.private.schedule-cron:0 0 3 * * *}")  // 03:00, offset from public 02:00 and expiry 08:00
@SchedulerLock(name = "PrivateScanScheduler_scheduledPrivateScan",
               lockAtMostFor = "PT1H", lockAtLeastFor = "PT10M")
public void scheduledPrivateScan() { ... }
```

Cron offset rationale: public scan 02:00 (`application.yml:112`), private sweep 03:00, expiry sweep 08:00 — private/public scans complete and certs are fresh before the 08:00 evaluation, and the two scan jobs don't contend for the ShedLock window.

### 6.2 Interaction with `queueScanJob` / agent claim flow

The sweep iterates enabled private targets that have an assigned agent and calls the existing `AgentService.queueScanJob(target)` (`AgentService.java:247-273`). That method already:
- de-dupes pending work (`existsByTargetIdAndStatusIn(PENDING, CLAIMED)`, `:261-262`) → a target with an unprocessed job won't double-queue;
- enforces `SubscriptionGuard.assertScansAllowed` (`:254`) → suspended orgs are skipped;
- requires an assigned agent (`:258-259`) → targets without an agent are skipped (log + continue, don't throw inside the sweep loop).

Agents pick up via the existing claim path (`pollJobs` → `claimPendingJobsWithLock` FOR UPDATE SKIP LOCKED, `:145-146`), report via `submitResult`, which now calls `ExpiryEvaluationService.evaluateAndNotify(cert, SCHEDULED)`.

### 6.3 Distinguishing sweep vs user-triggered jobs (for FORCE mode)

`agent_scan_jobs` currently has no trigger-origin marker. To let `submitResult` choose `SCHEDULED` vs `FORCE`, add a nullable column:

```sql
-- V32__add_scan_job_trigger_source.sql  (Phase 2 — shipped)
ALTER TABLE agent_scan_jobs ADD COLUMN trigger_source VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED';
-- values: SCHEDULED | USER ; user-force scans set USER, sweep sets SCHEDULED
```

`queueScanJob` gains an overload carrying the source; `TargetService.triggerScan` (user path) passes `USER`, the sweep passes `SCHEDULED`. `submitResult` reads `job.triggerSource` to choose the evaluation mode. (Still additive — new column with default; existing rows validate.) For the **direct/public** force path there's no job row, so `triggerScan`'s synchronous `scanTarget` call simply passes `FORCE` straight through.

### 6.4 Load implications — stagger / batch

To avoid flooding agents when the sweep enqueues many targets at 03:00:
- The sweep only **enqueues** PENDING jobs; agents drain them at their own poll cadence (default 30s) capped by `max_targets` per poll (`AgentService.pollJobs` caps at `agent.getMaxTargets()`, `:145-146`; `PollLoop` caps per tick). So enqueue is cheap and draining is naturally rate-limited per agent — **no thundering herd at the agent.**
- Server-side enqueue cost: batch the inserts and process in chunks (e.g., 500 targets per transaction) to bound transaction size; the `existsByTargetIdAndStatusIn` guard prevents pile-up across days.
- `lockAtMostFor=PT1H` bounds a stuck sweep; the existing `resetStaleClaimedJobs` (`AgentService.java:303-318`) recovers jobs an offline agent never finished.
- Config knobs: `app.scanning.private.schedule-cron`, and an optional `app.scanning.private.enqueue-batch-size` (default 500).

## 7. Call-site wiring summary

| Path | File:line | Change |
|---|---|---|
| Daily expiry sweep | `CertificateExpiryScheduler.java:74-93` | Replace inline severity + boolean-stamp logic with `expiryEvaluationService.evaluateAndNotify(expiring, SCHEDULED)`. |
| Direct/public scan persist | `SslScannerService.java:137` (after `certRepository.save`) | Add `expiryEvaluationService.evaluateAndNotify(record, mode)` — `mode` is `FORCE` when reached via `triggerScan`, `SCHEDULED` via the nightly public sweep. Use resolved `warningDays` for `determineStatus`. |
| Agent FULL | `AgentService.java:230` | Add evaluate call after save; mode from `job.triggerSource`. |
| Agent DELTA | `AgentService.java:243` | Same. |
| `determineStatus` divergence | `AgentService.java:361-366` | Stop hardcoding `30`; use resolved `warningDays`. |
| Dispatch signature | `NotificationService.java:93` | Return `void`; remove boolean. |
| Force debounce | `TargetService.triggerScan:199-219` & `submitResult` | Apply `force-scan-debounce-seconds` against prior `lastScannedAt`. |

`SslScannerService.scanTarget` (used by force) vs `scanTargets`/`scheduledPublicScan` (sweep) must thread the mode through; simplest is a `scanSingleTarget(target, mode)` overload, defaulting sweep callers to `SCHEDULED`.

## 8. Migration impact

All schema work is **additive**. Because `notification_settings` (§3) is deferred to a later phase, `trigger_source` shipped first and took **V32**; the settings table moves to **V33**:
1. `V32__add_scan_job_trigger_source.sql` (§6.3) — **shipped (Phase 2)** — new `NOT NULL DEFAULT 'SCHEDULED'` column on `agent_scan_jobs`.
2. `V33__create_notification_settings.sql` (§3.1) — **deferred (Phase 3)** — new table, two partial unique indexes, check constraint, `updated_at` trigger.

No drops/renames/type changes → `ddl-auto: validate` (`application.yml:23-24`) stays green. Max migration was **V31** (`V31__targets_preferred_ca.sql`) before this work. Do not let Hibernate create these tables.

## 9. GAPS.md entries to add

Add to `docs/architecture/GAPS.md`:

- **(new, HIGH) `dispatchExpiryAlert` `@Async`+boolean dedup bug.** `NotificationService.java:93-127` is `@Async` and returns `boolean`; `@EnableAsync` active (`CertGuardApplication.java:9`); caller `CertificateExpiryScheduler.java:88-92` consumes it synchronously → `stampAlertSentAt` never runs → `last_alert_sent_at` never written → every in-window cert re-alerts daily (alert storm); `alertsSent` logs 0. Fixed by this RFC §4. *(N8 only noted the dedup mechanism was undocumented, not that it's broken.)*
- **(new, HIGH) No post-scan expiry-notification hook.** Neither scan write-path (`SslScannerService.persistCertificates:117-149`, `AgentService.processFull/processDelta:208-244`) evaluates expiry or notifies; alerts fire only on the 08:00 cron. Manual/force scans never notify until the next morning. Closed by this RFC §2/§7.
- **(new, MEDIUM) Expiry thresholds not tenant-scoped.** `warning-days`/`critical-days` are app-wide `@Value` (`application.yml:98-100`), read in three classes with `AgentService.determineStatus:364` hardcoding `30`. No per-org/per-target override. Closed by this RFC §3.

## 10. Open items / non-goals

- Channel transport (SMS/Slack/etc.) remains "Coming Soon" — out of scope; this RFC touches policy + dispatch routing only.
- The `/api/v1/org/...` (singular) vs `/api/v1/organizations/{orgId}/...` convention mismatch is flagged, not resolved here — backend-engineer to confirm before adding the settings endpoints.
- A future `GET/PUT /api/v1/org/notification-channels` controller (the org channel table has no controller today, only a repository) is noted but deferred.

## 11. Ownership

- **backend-engineer:** `ExpiryEvaluationService`; `notification_settings` table + entity/repo; `V32`/`V33` migrations; the `@Async`→`void` + AFTER_COMMIT refactor; `PrivateScanScheduler`; `trigger_source` column + `queueScanJob` overload; force-scan debounce; settings controllers.
- **frontend-engineer:** per-org and per-target notification-settings UI consuming the §3.4 endpoints, including the "inherited vs override" affordance and threshold validation; surfacing "last alerted at" so a deduped/debounced force-scan with no fresh email is explainable.
