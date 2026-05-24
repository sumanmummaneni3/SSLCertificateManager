# RFC 0001 — User Removal and Org Offboarding

**Status:** Approved — decisions recorded 2026-05-24
**Author:** CertGuard Architect
**Audience:** backend-engineer, frontend-engineer

---

## Confirmed Decisions

| # | Question | Decision |
|---|---|---|
| 1 | Retention window before purge | **Indefinite** — no auto-purge under any circumstance; purge only executes on explicit operator request |
| 2 | Data export format | **CSV only** — export targets, certificates, and members before purge |
| 3 | Hard delete | **Supported** — API requires a confirmation token; UI must present a destructive-action warning dialog before calling the purge endpoint |
| 4 | Auth-service responsibility | **Delete only** — auth-service deletes its `auth_users` row outright; it has no anonymisation concern; it is purely an authorization service |
| 5 | MSP client detach | **Not allowed** — a client org cannot detach and become standalone; if a client leaves, its data is archived or deleted on request, same lifecycle as any other org |

---

## 1. Goals and Non-goals

**Goals**
- Safe, auditable lifecycle for removing org members, offboarding entire orgs/MSPs, and deleting individual user accounts.
- Guards that prevent destructive footguns (last admin, MSP with live clients, self-removal).
- Clean coordination with auth-service (which owns `auth_users`).
- CSV data export before any purge.

**Non-goals**
- Auto-purge based on time or subscription state (prohibited by decision #1).
- Any export format other than CSV.
- MSP client detachment to standalone org (prohibited by decision #5).
- Redesigning the existing soft-archive of orgs (already in `AdminService.archiveOrg`).
- Redesigning the invite flow.

---

## 2. Terminology

| Term | Meaning |
|---|---|
| **Revoke** | Set `org_members.invite_status = REVOKED`. Reversible. User account untouched. |
| **Archive** | Soft-delete an org (`archived_at/by/reason`). Reversible via restore. |
| **Suspend** | New phase. Org read-only; logins blocked; scans paused. Reversible. |
| **Purge** | Hard delete. Irreversible. Only on explicit operator request. Never automatic. |
| **Anonymise** | Replace PII on `users` row with tombstone values; preserve UUID for FK integrity in audit tables. Used when hard-delete is blocked by audit references. |

---

## 3. Scenario A — Org/MSP Admin Removes a Member

### 3.1 Authorization matrix

| Caller's `OrgMemberRole` | Target's role | Allowed |
|---|---|---|
| `ADMIN` | `ENGINEER` | Yes |
| `ADMIN` | `VIEWER` | Yes |
| `ADMIN` | another `ADMIN` | **No** — 403; PLATFORM_ADMIN only |
| `ADMIN` | self | **No** — 409 |
| `ENGINEER` / `VIEWER` | anyone | **No** — 403 |
| `PLATFORM_ADMIN` | any | Yes |

### 3.2 Flow

On `DELETE /api/v1/org/members/{userId}` (extend existing `TeamService.revokeMember`):

1. Load `OrgMember` by `(orgId, userId)` — 404 if missing.
2. Authorization checks per matrix above.
3. `assertNotSelf()` — 409 if caller == target.
4. `assertNotLastAdmin()` — 409 if this would leave the org with 0 ADMINs.
5. `UPDATE org_members SET invite_status='REVOKED', revoked_at=now(), revoked_by_user_id=callerId, revoke_reason=?`.
6. `InvitationService.cancelPendingForEmailAndOrg(email, orgId)` — sets `invitations.cancelled_at = now()`, `cancelled_reason = 'MEMBER_REMOVED'`.
7. `TokenRevocationService.revokeForUserInOrg(userId, orgId)`.
8. Write to `org_audit` (all callers) and `platform_admin_audit` (if caller is PLATFORM_ADMIN).
9. Fire `MemberRemovedEvent` → `NotificationService` sends email async (`@TransactionalEventListener(AFTER_COMMIT)`).

### 3.3 Error codes

| Code | Type URI | Condition |
|---|---|---|
| 403 | `urn:problem:not-org-admin` | Caller lacks ADMIN role |
| 403 | `urn:problem:cannot-remove-admin` | Target is ADMIN; caller is not PLATFORM_ADMIN |
| 409 | `urn:problem:self-removal-forbidden` | Caller == target |
| 409 | `urn:problem:last-admin-protected` | Would leave org with 0 admins |
| 404 | `urn:problem:member-not-found` | No `org_members` row |

### 3.4 API change

`DELETE /api/v1/org/members/{userId}` — add optional body `{ "reason": "..." }`. Response: `204 No Content`.

---

## 4. Scenario B — Platform Admin Offboards an Org or MSP

### 4.1 Lifecycle

```
ACTIVE → WARNING → SUSPENDED → ARCHIVED → [PURGE on explicit request]
              ↑_______↓ (cancel/reactivate)
```

No phase transitions happen automatically. Every transition is an explicit API call from a PLATFORM_ADMIN. There is no scheduled or timer-triggered purge.

### 4.2 What each phase does

| Phase | Targets | Agents | Members | Subscription | Logins |
|---|---|---|---|---|---|
| **WARNING** | unchanged | unchanged | unchanged | flagged | allowed |
| **SUSPENDED** | `disableAllForOrg()` | mark offline | JWTs blocked via epoch bump | `SUSPENDED` | **blocked — 423** |
| **ARCHIVED** | disabled (existing) | `revokeAllForOrg()` (existing) | kept | `cancelled_at` set | blocked |
| **PURGED** | deleted | deleted | `org_members` deleted; users evaluated (§4.4) | deleted | n/a |

### 4.3 MSP cascade rules

- Suspend MSP → all child orgs suspend in the same transaction.
- Archive MSP → existing cascade in `AdminService.archiveCascade()` already handles this.
- Purge MSP → all children must already be ARCHIVED; otherwise 409. There is no auto-cascade on purge — each child must be explicitly archived first.
- **MSP client detach is not supported.** A client org follows the same archive/purge lifecycle as any org. It cannot become a standalone SINGLE org.

### 4.4 Purge flow

Purge is two-step, always explicit, never automatic.

**Step 1 — Request (impact summary + confirmation token):**

`POST /api/v1/admin/orgs/{orgId}/purge-request`

Response:
```json
{
  "confirmationToken": "<uuid, 10-min TTL>",
  "summary": {
    "orgName": "Acme Corp",
    "targetCount": 42,
    "agentCount": 3,
    "memberCount": 8,
    "certificateCount": 156,
    "childOrgCount": 0,
    "csvExportReady": false
  }
}
```

**Step 2 — Execute:**

`POST /api/v1/admin/orgs/{orgId}/purge`
Body: `{ "confirmationToken": "...", "cascadeUsers": false }`

Service executes in a single transaction:
1. Assert org is ARCHIVED (not just suspended).
2. Assert no non-archived child orgs exist (for MSP).
3. Trigger CSV export (blocking) → write to `exports/{orgId}-{timestamp}.csv`.
4. Delete in order: `certificate_records`, `agent_scan_jobs`, `targets`, `agents`, `invitations`, `org_members`, `subscriptions`, `org_audit`, `organizations`.
5. For each affected `userId`: evaluate orphan status (§4.5).
6. Write `platform_admin_audit` row with `metadata_json` containing deletion counts.
7. Call auth-service: `POST /internal/auth/orgs/{orgId}/delete` — auth-service deletes all `auth_users` rows for that org's users.

### 4.5 Orphaned-user handling during purge

After deleting `org_members` for the org, for each affected `userId`:
- **User has other active org memberships** → leave `users` row; if `users.org_id` pointed at the purged org, repoint to any remaining org.
- **User has no remaining memberships** → mark `users.status = 'ORPHANED'`. Does **not** auto-purge. Stays until a Platform Admin explicitly runs Scenario C against that user.

### 4.6 CSV export content

Three files bundled as a zip:
- `members.csv` — userId, email, name, role, joined_at
- `targets.csv` — hostname, port, type, status, last_scanned_at, cert_expiry
- `certificates.csv` — hostname, port, issuer, subject, not_before, not_after, status

Export endpoint: `GET /api/v1/admin/orgs/{orgId}/export` → returns `application/zip`.
Must be available from ARCHIVED state (before purge). PA downloads before or the purge flow generates it automatically before deletion.

### 4.7 API surface (Scenario B)

| Method | Path | Role | Notes |
|---|---|---|---|
| `POST` | `/api/v1/admin/orgs/{orgId}/warn-offboarding` | PA | body `{ reason }` |
| `POST` | `/api/v1/admin/orgs/{orgId}/cancel-offboarding` | PA | clears WARNING |
| `POST` | `/api/v1/admin/orgs/{orgId}/suspend` | PA | body `{ reason }` |
| `POST` | `/api/v1/admin/orgs/{orgId}/reactivate` | PA | SUSPENDED → ACTIVE |
| `DELETE` | `/api/v1/admin/orgs/{orgId}` | PA | existing — archive |
| `POST` | `/api/v1/admin/orgs/{orgId}/restore` | PA | existing — restore from ARCHIVED |
| `GET` | `/api/v1/admin/orgs/{orgId}/export` | PA | download CSV zip |
| `POST` | `/api/v1/admin/orgs/{orgId}/purge-request` | PA | returns token + summary |
| `POST` | `/api/v1/admin/orgs/{orgId}/purge` | PA | body `{ confirmationToken, cascadeUsers }` |
| `GET` | `/api/v1/admin/orgs/{orgId}/offboarding-status` | PA | current phase + blockers |

### 4.8 UI requirements (Scenario B)

Before calling `POST .../purge`, the UI **must** display a destructive-action confirmation dialog containing:
- Org name, counts from the impact summary.
- "This action is permanent and cannot be undone."
- A text field where the operator must type the org name to confirm.
- "Download CSV export" button (should be clicked before confirming).
- Only then enable the final "Permanently Delete" button.

---

## 5. Scenario C — Platform Admin Removes a User Entirely

### 5.1 Anonymise vs hard-delete

Both modes are supported.

- **Anonymise** (default, safe): PII replaced, UUID kept for FK integrity in audit tables.
- **Hard delete**: only when `platform_admin_audit` and `org_audit` contain no references to this user. Service checks and returns 409 if references exist.

### 5.2 Anonymisation contract

| Column | New value |
|---|---|
| `email` | `deleted-{uuid}@tombstone.certguard.invalid` |
| `name` | `Deleted User` |
| `google_sub` | `NULL` |
| `status` | `DELETED` |
| `deleted_at` | `now()` |
| `deleted_by_user_id` | callerId |
| `deletion_reason` | `GDPR_REQUEST` \| `PA_ACTION` \| `ORG_PURGE_CASCADE` |

Unique index on `users.email` excludes `DELETED` rows, allowing the email address to be re-registered later.

### 5.3 Flow

`DELETE /api/v1/admin/users/{userId}?mode=ANONYMISE|HARD_DELETE`

1. Load user — 404 if missing.
2. `assertNotLastPlatformAdmin()` — 409 if removing would leave 0 PLATFORM_ADMINs.
3. `UPDATE org_members SET invite_status='REVOKED', revoked_at=now()` for all memberships.
4. `InvitationService.cancelAllPendingForEmail(email)`.
5. `TokenRevocationService.revokeAllTokens(userId)`.
6. If `HARD_DELETE`: assert no audit references → 409 `urn:problem:audit-references-exist` if found.
7. Apply anonymisation or hard-delete to `users` row.
8. **Call auth-service**: `DELETE /internal/auth/users/{email}` — auth-service deletes its `auth_users` row outright. On failure: enqueue to `pending_auth_sync`; server-side deletion is not rolled back.
9. Write `platform_admin_audit` (USER_DELETED, mode, reason).

### 5.4 UI requirements (Scenario C)

Before calling the delete endpoint, the UI **must** show a destructive-action dialog:
- User email, name, org count, membership list.
- Mode selector: "Anonymise (recommended)" vs "Hard delete".
- Hard delete shows additional warning: "This permanently removes all account data. The email address will be released and can be re-registered."
- Operator must type the user's email to confirm.

### 5.5 API surface (Scenario C)

| Method | Path | Role | Notes |
|---|---|---|---|
| `GET` | `/api/v1/admin/users/{userId}/removal-impact` | PA | dry-run: membership list, audit ref count, mode eligibility |
| `DELETE` | `/api/v1/admin/users/{userId}` | PA | query param `mode`, body `{ reason }` |

---

## 6. Auth-service coordination

Auth-service is an authorization service only. Its sole responsibility on user/org deletion is to **delete** the relevant `auth_users` rows. It does not anonymise, archive, or export.

**Internal API (server → auth-service):**

| Method | Path | Called when |
|---|---|---|
| `DELETE` | `/internal/auth/users/{email}` | User is removed (Scenario C) |
| `POST` | `/internal/auth/orgs/{orgId}/delete` | Org is purged (Scenario B step 4.4) — deletes all `auth_users` for that org's members |

Authentication: shared `X-CG-Internal-Token` header (pre-shared secret, not a user JWT).

On auth-service unavailability: server writes to `pending_auth_sync` outbox and continues. A `AuthSyncScheduler` retries every 5 minutes. Alert fires when rows in `pending_auth_sync` are older than 1 hour.

---

## 7. Token Revocation

Current 8-hour HS256 JWTs have no server-side invalidation. New `TokenRevocationService`:

- `revokeForUserInOrg(userId, orgId)` — used by Scenario A.
- `revokeAllTokens(userId)` — used by Scenario C.
- `revokeAllForOrg(orgId)` — bumps `org_token_epoch`; `JwtAuthFilter` rejects tokens where `iat < epoch`.
- `JwtAuthFilter` checks revocation after signature validation on every protected request.

**Storage:**
- Phase 1: Caffeine in-memory + `revoked_tokens` table for restart durability.
- Phase 2: Redis (when horizontally scaled).

Risk: Caffeine is per-instance SPOF. Documented in `GAPS.md`.

---

## 8. Schema changes (5 Flyway migrations)

Use the next available `V` number from `src/main/resources/db/migration/`.

### V{N}__org_audit.sql
```sql
CREATE TABLE org_audit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    actor_user_id   UUID NOT NULL REFERENCES users(id),
    action          VARCHAR(64) NOT NULL,
    target_type     VARCHAR(32),
    target_id       UUID,
    metadata_json   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_org_audit_org_created ON org_audit(org_id, created_at DESC);
```

### V{N+1}__org_members_revocation.sql
```sql
ALTER TABLE org_members
    ADD COLUMN revoked_at           TIMESTAMPTZ,
    ADD COLUMN revoked_by_user_id   UUID REFERENCES users(id),
    ADD COLUMN revoke_reason        VARCHAR(255);

ALTER TABLE invitations
    ADD COLUMN cancelled_at         TIMESTAMPTZ,
    ADD COLUMN cancelled_reason     VARCHAR(64);
```

### V{N+2}__organizations_lifecycle.sql
```sql
ALTER TABLE organizations
    ADD COLUMN status                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN warning_issued_at     TIMESTAMPTZ,
    ADD COLUMN suspended_at          TIMESTAMPTZ,
    ADD COLUMN suspended_by_user_id  UUID REFERENCES users(id),
    ADD COLUMN offboard_reason       VARCHAR(500),
    ADD COLUMN purge_requested_at    TIMESTAMPTZ,
    ADD CONSTRAINT chk_org_status
        CHECK (status IN ('ACTIVE','WARNING','SUSPENDED','ARCHIVED','PURGE_PENDING'));

CREATE INDEX idx_organizations_status ON organizations(status)
    WHERE status <> 'ACTIVE';
```

### V{N+3}__users_deletion.sql
```sql
ALTER TABLE users
    ADD COLUMN status               VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN deleted_at           TIMESTAMPTZ,
    ADD COLUMN deleted_by_user_id   UUID REFERENCES users(id),
    ADD COLUMN deletion_reason      VARCHAR(64);

-- Unique index on email that ignores deleted/tombstone rows
DROP INDEX IF EXISTS users_email_key;  -- replace existing unique constraint
CREATE UNIQUE INDEX uq_users_email_active ON users(email)
    WHERE status = 'ACTIVE';
```

### V{N+4}__token_revocation.sql
```sql
CREATE TABLE revoked_tokens (
    id           BIGSERIAL PRIMARY KEY,
    user_id      UUID,
    org_id       UUID,
    revoked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    reason       VARCHAR(64)
);
CREATE INDEX idx_revoked_tokens_user ON revoked_tokens(user_id, expires_at);
CREATE INDEX idx_revoked_tokens_org  ON revoked_tokens(org_id,  expires_at);

CREATE TABLE org_token_epoch (
    org_id   UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    epoch    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pending_auth_sync (
    id              BIGSERIAL PRIMARY KEY,
    operation       VARCHAR(32)  NOT NULL,  -- DELETE_USER | DELETE_ORG_USERS
    payload_json    JSONB        NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

---

## 9. What ships without migrations (Phase 1)

Implement immediately against existing schema:

- Authorization guards in `TeamService.revokeMember`: role matrix, self-removal, last-admin.
- Cancel pending invitations on revoke (repurpose existing `invitations.used_at`).
- Email notification via existing `NotificationService` + new template.
- PLATFORM_ADMIN audit entries to existing `platform_admin_audit`.

---

## 10. Edge cases and guards

| # | Scenario | Guard | Error |
|---|---|---|---|
| 1 | OrgAdmin removes self | reject | 409 `self-removal-forbidden` |
| 2 | Last ADMIN in org | reject | 409 `last-admin-protected` |
| 3 | OrgAdmin removes another ADMIN | reject | 403 `cannot-remove-admin` |
| 4 | PA removes last PA | reject | 409 `last-platform-admin` |
| 5 | Purge MSP with non-archived clients | reject | 409 `msp-has-active-clients` |
| 6 | MSP client tries to detach | not supported — no endpoint | 404 / 405 |
| 7 | Suspend already-suspended org | idempotent 200, no-op | — |
| 8 | Hard-delete user with audit refs | reject | 409 `audit-references-exist` |
| 9 | Re-invite tombstoned email | allowed (unique index excludes DELETED rows) | — |
| 10 | Removed user has active agents | `revokeAllForOrg()` cascades via existing method | — |
| 11 | auth-service down during user delete | enqueue `pending_auth_sync`; local delete succeeds | — |
| 12 | User belongs to multiple orgs, one purges | only that org's `org_members` row removed; user stays | — |
| 13 | Confirmation token replay | one-time use, 10-min TTL, consumed on use | 410 `purge-token-expired` |
| 14 | Purge without CSV export | export is generated automatically as part of the purge step 3 | — |
| 15 | Restore archived MSP whose clients were purged | allowed; response flags missing children | — |

---

## 11. Implementation phasing

| Phase | Work | Migrations | Notes |
|---|---|---|---|
| **1 — Guards** | Scenario A guards + notification + PA audit | none | Ship first; unblocks UI |
| **2 — Revocation + audit** | `org_audit`, revocation columns, Caffeine token blocklist | V{N}, V{N+1} | Required before Scenario B |
| **3 — Org lifecycle** | Suspend/warn/reactivate endpoints + CSV export | V{N+2} | Requires UI offboarding panel |
| **4 — Purge + user delete** | Purge endpoints, user anonymise/delete, auth-service client + outbox | V{N+3}, V{N+4} | Requires UI destructive dialogs |
