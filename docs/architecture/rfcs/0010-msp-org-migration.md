# RFC 0010: Migrating an Organization from one MSP to another (Platform-Admin gated)

- **Status:** Proposed
- **Date:** 2026-06-21
- **Scope:** CertGuard Cloud server (`server/`)
- **Trigger model (v1):** Out-of-band — source MSP and impacted Org email the Platform Admin, who then executes the migration in the system. Not self-service.

---

## 0. The single most important finding

The MSP↔Org relationship lives entirely in one column: **`organizations.parent_org_id`**. There is no separate "MSP membership" table, and the denormalized `org_id` columns scattered across child tables all point at the **client org's own UUID**, which is *preserved* through the move. The org keeps its identity; only its parent pointer changes.

The migration is therefore **not** a sprawling cascade re-write. It is a one-column re-parent plus a few access-control cleanups, done in **one atomic DB transaction**.

Evidence:
- `organizations.parent_org_id` is the only MSP link — `Organization.java:28-30`.
- `Target.org_id`, `Agent.org_id`, `CertificateRecord.org_id`, `AgentScanJob.org_id`, `Subscription.org_id`, `OrgMember.org_id`, `Location.org_id`, `Invitation.org_id`, `User.org_id` all reference the **client org**, not the MSP.
- MSP scope is computed *per request* from the live parent pointer — `JwtAuthenticationFilter.java:186-192` → `OrganizationRepository.findActiveChildIds(mspOrgId)`. Source-MSP top-down access evaporates and target-MSP access appears automatically on the next request after the pointer flips.

---

## 1. Confirmed product decisions (2026-06-21)

| # | Decision |
|---|---|
| **1. Quota / billing** | MSP-B inherits the negotiated certs from MSP-A. Cert quota stays the standard tier (10 free certs, paid beyond). The `subscriptions` row is org-scoped (`Subscription.java:14`) and travels with the org as-is — no re-quote, no reset. |
| **2. In-flight scans** | Do **not** block on PENDING/CLAIMED scan jobs. Report the count at migration time and record it in the audit row. Jobs keep running safely (all keys are org-scoped; org id is stable). |
| **3. Target-MSP onboarding** | Target MSP must be **onboarded first** (must already exist, be `MSP` type, and active). Only then can the Platform Admin move the impacted Org into it. No auto-membership — target MSP gets top-down access via `findActiveChildIds`. Onboarding-first is a **precondition**, not an automatic grant. |
| **4. Undo** | **Per-Org reverse is supported** for mistaken moves. Undo must also **restore** the source-MSP staff `org_members` rows (and their tokens) that the forward move revoked. This requires persisting *which* member rows were revoked (see §5, §6) — richer than "just call transfer again." |

---

## 2. Data model / multi-tenancy impact

### 2.1 Re-point surface (what actually changes)

| Reference | Where | Re-point? |
|---|---|---|
| `organizations.parent_org_id` | `Organization.parentOrg` (`Organization.java:28-30`) | **YES — the only structural change.** MSP-A → MSP-B. |
| MSP access set (request-time) | `findActiveChildIds(mspOrgId)` (`JwtAuthenticationFilter.java:186-192`) | No — derived live; self-heals. |
| `MspAccessGuard.canAccessOrg()` | `MspAccessGuard.java:17-25` | No — reads `parentOrg.getId()` live. |
| Billing owner | `findBillingOwner = COALESCE(parent_org_id, id)` (`OrganizationRepository.java:35-36`) | No code change; billing attribution moves to MSP-B (intended — Decision 1). |

### 2.2 What does NOT move (valid because client-org UUID is unchanged)

`targets`, `agents`, `certificate_records`, `agent_scan_jobs`, `locations`, `invitations`, `subscriptions`, `org_members`, `users` — all keyed on the client org id. Cert inventory, scan history, per-target `notification_channels` JSONB (`Target.java:72-75`), org `contact_email`, and the org's own users are all untouched. **This satisfies "same certs, same org, no data loss" by construction.**

### 2.3 The one genuine residue: source-MSP staff as direct members of the client org

`MspClientService.createClient` (`MspClientService.java:78-87`) auto-inserts the creating MSP user as an `ADMIN` `OrgMember` of the client org. These are *direct memberships*, not derived from `parent_org_id`. After re-parenting they **persist** and must be explicitly revoked + token-revoked — otherwise MSP-A retains access. This is the main correctness item, and the rows we revoke must be recorded to support Undo (Decision 4).

---

## 3. Migration workflow (forward) — one atomic transaction

### Preconditions (each maps to a ProblemDetail in §4)
1. Caller is `PLATFORM_ADMIN` (`@PreAuthorize("hasRole('PLATFORM_ADMIN')")`, per `AdminController.java:32-34`).
2. `orgId` exists and is not archived (`archivedAt == null`).
3. `orgId` is a **client** org (`orgType == SINGLE` and `parentOrg != null`).
4. `expectedSourceMspId` (recommended) matches the current `parentOrg.id` — stale-email / double-submit guard.
5. `targetMspOrgId` exists, `orgType == MSP`, not archived (Decision 3 — onboarded first).
6. `targetMspOrgId != current parent` (reject no-op) and `!= orgId` (cannot parent to self).
7. `reason` (required); `referenceTicket` (recommended — the out-of-band email/ticket id).

### Transaction
1. `SELECT ... FOR UPDATE` the org row (prevent concurrent transfers).
2. Re-read parent; re-validate preconditions 2–6 inside the tx.
3. `org.setParentOrg(targetMsp)` and save. **This is the whole structural move.**
4. Revoke source-MSP staff memberships in the client org: for each `org_member` of the client org whose `user.org_id == sourceMspId`, set `revokedAt` / `revokedByUserId` / `revokeReason` (existing fields, `OrgMember.java:42-49`) and queue a JWT revocation for that `(userId, clientOrgId)` pair via the existing `TokenRevocationService` path. **Capture the affected member ids** for the audit row (Decision 4 / Undo).
5. Insert one immutable `org_migration_audit` row (§5), `direction = FORWARD`, including `revoked_member_ids` and `in_flight_scan_job_count`.
6. Commit.

### In-flight work (Decision 2)
Count `agent_scan_jobs` in `PENDING`/`CLAIMED` for the org, record it, **do not block**. A CLAIMED job completing post-move writes to the same `certificate_records` under the same org.

### Post-commit (best-effort, idempotent, outside tx)
Email source-MSP contact, target-MSP contact, and org `contact_email` via `NotificationService`. Failure must not roll back the move.

### Post-conditions
- `parent_org_id == targetMspOrgId`; org leaves MSP-A's client list, joins MSP-B's.
- `findBillingOwner(orgId)` returns MSP-B.
- Source-MSP staff lose top-down access (auto) + revoked direct memberships + revoked tokens.
- Exactly one immutable `FORWARD` migration record exists.

---

## 4. API design

New endpoints on the platform-admin surface (`/api/v1/admin/**`, class-level `PLATFORM_ADMIN` — `AdminController.java:32-34`):

```
POST /api/v1/admin/orgs/{orgId}/transfer        # forward move
POST /api/v1/admin/orgs/{orgId}/transfer/undo   # reverse the most recent FORWARD migration (Decision 4)
```

**Transfer request:**
```json
{
  "targetMspOrgId": "uuid",
  "expectedSourceMspId": "uuid (optional, recommended)",
  "reason": "string (required)",
  "referenceTicket": "string (optional — out-of-band email/ticket ref)"
}
```

**Undo request:**
```json
{
  "migrationId": "uuid (the FORWARD record to reverse)",
  "reason": "string (required)"
}
```
Undo re-parents the org back to `source_msp_org_id`, **clears `revoked_at`/`revoke_reason` on exactly the `revoked_member_ids` recorded** (restoring MSP-A staff access; their tokens re-validate on next login), and writes a new immutable `REVERSE` audit record linked to the original via `reverses_migration_id`. Undo is itself idempotent-guarded: rejects if the org's current parent no longer matches the FORWARD record's target (the org moved again since).

**Success `200 OK`** → `OrgResponse` (now showing the new `parentOrgId`) plus a summary block (`migrationId`, `revokedMemberCount` / `restoredMemberCount`, `inFlightScanJobCount`).

**RFC 9457 ProblemDetail cases** (typed URIs `https://certguard.dev/problems/<kebab>`, per `GlobalExceptionHandler.java:53-59`):

| Condition | HTTP |
|---|---|
| Caller not platform admin | 403 (`@PreAuthorize` denial → 403 per commit 2186fa5) |
| `orgId` / `targetMspOrgId` / `migrationId` not found or archived | 404 |
| Target org not `MSP` type | 409 |
| Org not a client org (no parent / is MSP) — `problems/org-not-transferable` | 409 |
| `expectedSourceMspId` mismatch (already moved) — `problems/source-msp-mismatch` | 409 |
| Target == current parent (no-op) — `problems/no-op-transfer` | 409 |
| Undo: current parent ≠ FORWARD target (moved again) — `problems/undo-stale` | 409 |
| Missing `reason` | 400 |

This is **not** routed through the `X-Acting-As-Org` impersonation path — the platform admin acts as themselves against an admin endpoint; org id is a path variable. Keep it on the dedicated migration audit (§5), off the act-as audit.

---

## 5. Audit / compliance

High-privilege, money-relevant, triggered off a manual email → its own **immutable** table, modeled on the append-only `PlatformAdminAudit` (`PlatformAdminAudit.java:12-48`; no `BaseEntity`, DB-managed `created_at`, no update path).

`org_migration_audit`:

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `direction` | VARCHAR(16) NOT NULL | `FORWARD` / `REVERSE` |
| `reverses_migration_id` | UUID NULL | FK to the `FORWARD` row, set on undo |
| `org_id` | UUID NOT NULL | migrated client org |
| `org_name` | VARCHAR(255) | snapshot |
| `source_msp_org_id` | UUID NOT NULL | MSP-A |
| `source_msp_name` | VARCHAR(255) | snapshot |
| `target_msp_org_id` | UUID NOT NULL | MSP-B |
| `target_msp_name` | VARCHAR(255) | snapshot |
| `acting_user_id` | UUID NOT NULL | platform admin |
| `acting_user_email` | VARCHAR(255) NOT NULL | snapshot |
| `reason` | TEXT NOT NULL | |
| `reference_ticket` | VARCHAR(255) | out-of-band email/ticket ref |
| `revoked_member_ids` | JSONB | org_member ids revoked on FORWARD — drives Undo restore (Decision 4) |
| `revoked_member_count` | INT NOT NULL DEFAULT 0 | |
| `in_flight_scan_job_count` | INT NOT NULL DEFAULT 0 | snapshot (Decision 2) |
| `created_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | immutable |

No `updated_at`, no delete path. Surface via the existing admin audit feed (`AdminController.getAudit`, `AdminController.java:115-125`) or a new `/api/v1/admin/migrations` view.

---

## 6. Auth-service / JWT implications

Per `project_jwt_arch` / `project_gateway_public_paths`: auth-service mints RS256 JWTs; the gateway injects `X-CG-User-Id/Org-Id/Role/Email/Platform-Admin`, which the core server trusts (`JwtAuthenticationFilter.java:66-99`).

- The JWT carries the user's **home org**, not the MSP. The moved org's own users keep their `org_id` claim (the client org, unchanged) → **no token re-minting for them.**
- Source-MSP staff revocation is enforced entirely by the core server's `revoked_tokens` table (`JwtAuthenticationFilter.java:120-127`), keyed on `(userId, orgId)`. **No auth-service change required for v1.**
- **Sign-off needed (Q-A):** confirm with the auth-service repo owner that `org_members` is mastered in the core DB (it is — `OrgMember` is a core entity) and that the auth-service holds no MSP→client cache requiring invalidation. No such cache was found in this repo.

---

## 7. Edge cases & risks

1. **Billing (Decision 1):** parent flip moves billing attribution to MSP-B instantly via `COALESCE(parent_org_id, id)`. Intended. Subscription + quota travel unchanged.
2. **In-flight scans (Decision 2):** safe to leave running; counted only.
3. **Agents:** `agents.org_id` = client org, unchanged — keep working, no re-enrollment.
4. **Notification configs:** org-scoped — untouched.
5. **Lingering source-MSP access (main risk):** top-down self-heals; direct `org_member` rows for MSP-A staff persist and MUST be revoked + token-revoked (§3 step 4) and recorded for Undo. Re-verify the MSP target-filter scoping (commits 2186fa5 / 76e2488 / f439f02) derives from `parent_org_id` / `accessibleOrgIds` (`JwtAuthenticationFilter.java:184-192`), not a cached MSP→org list.
6. **Rollback within a call:** single transaction — half-complete state impossible.
7. **Undo (Decision 4):** reverse re-parents to source and restores recorded `revoked_member_ids`; guarded against the org having moved again (`undo-stale` 409).
8. **Concurrent transfer / double-submit:** `FOR UPDATE` lock + `expectedSourceMspId` check → second attempt fails with 409 `source-msp-mismatch`.
9. **Edge — dual-role member:** a user who is both MSP-A staff *and* a genuine member of the client org would be wrongly cut off if we revoke purely on `user.org_id == sourceMspId`. v1 keys on home-org match; flag if an "added-by-MSP" provenance flag is needed (Q-B).

---

## 8. Flyway migration

Next version = **V36** (highest existing is V35). New table only; no change to existing tables (the move is data-only at runtime). Hibernate `ddl-auto: validate`.

```sql
-- V36__org_migration_audit.sql
CREATE TABLE org_migration_audit (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    direction                VARCHAR(16) NOT NULL,
    reverses_migration_id    UUID REFERENCES org_migration_audit(id),
    org_id                   UUID NOT NULL REFERENCES organizations(id),
    org_name                 VARCHAR(255),
    source_msp_org_id        UUID NOT NULL REFERENCES organizations(id),
    source_msp_name          VARCHAR(255),
    target_msp_org_id        UUID NOT NULL REFERENCES organizations(id),
    target_msp_name          VARCHAR(255),
    acting_user_id           UUID NOT NULL REFERENCES users(id),
    acting_user_email        VARCHAR(255) NOT NULL,
    reason                   TEXT NOT NULL,
    reference_ticket         VARCHAR(255),
    revoked_member_ids       JSONB,
    revoked_member_count     INT NOT NULL DEFAULT 0,
    in_flight_scan_job_count INT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_org_migration_audit_org_id     ON org_migration_audit(org_id);
CREATE INDEX idx_org_migration_audit_source_msp ON org_migration_audit(source_msp_org_id);
CREATE INDEX idx_org_migration_audit_target_msp ON org_migration_audit(target_msp_org_id);
CREATE INDEX idx_org_migration_audit_created_at ON org_migration_audit(created_at DESC);
```

`org_members` revocation reuses existing columns (`revoked_at`, `revoked_by_user_id`, `revoke_reason` — `OrgMember.java:42-49`); no migration there. The `OrgMigrationAudit` entity mirrors `PlatformAdminAudit`'s immutable, no-`BaseEntity` pattern so it validates cleanly.

---

## 9. Remaining sign-offs (not blocking design)

- **Q-A:** Auth-service owner confirms no MSP→client cache needs invalidation. (Expected: none.)
- **Q-B:** Do we need an "added-by-MSP" provenance flag on `org_members` to avoid cutting off a dual-role user on revoke? (Edge case; defer unless real.)

---

## Implementation handoff (backend)

1. `V36__org_migration_audit.sql` (above).
2. `OrgMigrationAudit` entity (immutable, mirrors `PlatformAdminAudit`) + repository.
3. `OrgMigrationService` (`@Transactional`, `readOnly=false`): forward + undo, with `FOR UPDATE` lock, member revocation/restoration via `TokenRevocationService`, audit write.
4. `AdminController`: `POST /transfer` and `POST /transfer/undo` + request/response DTOs.
5. Typed ProblemDetail entries in `GlobalExceptionHandler` for the new 409 cases.
6. Post-commit notification hook (best-effort).
7. Tests: forward happy-path, undo restore, source-MSP-mismatch guard, undo-stale guard, lingering-member revocation, in-flight-scan count.
