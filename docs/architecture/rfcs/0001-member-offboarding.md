# RFC-0001: Member offboarding and JWT revocation

- Status: Implemented (Phase 1 + Phase 2)
- Date: 2026-05-24
- Owners: Backend
- Related code:
  - `server/src/main/java/com/certguard/service/TeamService.java:150-251`
  - `server/src/main/java/com/certguard/service/TokenRevocationService.java`
  - `server/src/main/java/com/certguard/security/JwtAuthenticationFilter.java:119-127`

## 1. Problem

Removing a member from an organisation must be:

1. **Safe** — never strand an org with zero admins; never let a user remove
   themselves (locks themselves out before they finish handing off).
2. **Effective** — a removed user's in-flight JWT must stop working soon enough
   that they cannot keep operating after the UI says they're out.
3. **Auditable** — both the customer and the SaaS operator can prove who removed
   whom, when, and why.
4. **Idempotent** — re-issuing a DELETE for an already-removed member must not 500.

Phase 1 added safety guards. Phase 2 added the revocation half and the dual audit trail.

## 2. Phase 1 — safety guards

All implemented in `TeamService.revokeMember` at `TeamService.java:150-251`.

### 2.1 Self-removal block

`TeamService.java:156-158` — if `requestingUserId.equals(targetUserId)`, throw
`IllegalStateException("You cannot remove yourself — transfer admin access to
another member first")`. Surfaced as 409 by `GlobalExceptionHandler`.

### 2.2 Last-admin guard

`TeamService.java:186-194` — if the target's role is `ADMIN`, count accepted
admins in the org. If `<= 1`, throw `IllegalStateException`. Applies even when
the caller is a PLATFORM_ADMIN, to keep the org self-administrable.

### 2.3 Org-admin cross-removal block

`TeamService.java:171-183` — when the caller is not a PA:
- The caller must have `OrgMemberRole.ADMIN` and `InviteStatus.ACCEPTED` — otherwise `SecurityException` (403).
- An org admin **cannot** remove another `ADMIN`. Only a PLATFORM_ADMIN can.
  This prevents admin-vs-admin lockout fights inside a single tenant.

### 2.4 Pending-invitation cancellation

`TeamService.java:204-217` — when removing a member, every pending invitation
addressed to that member's email in the same org is closed:
`usedAt = now`, `cancelledAt = now`, `cancelledReason = "Member removed[: <reason>]"`.
This guarantees a removed user cannot re-enter by accepting a stale invite.

### 2.5 Idempotency

`TeamService.java:164-168` — if the target's `InviteStatus` is already `REVOKED`,
the method logs and returns without raising. The controller still emits 204 No
Content, so re-issued DELETEs are non-destructive.

## 3. Phase 2 — revocation, audit, notification

### 3.1 JWT revocation via TokenRevocationService

CertGuard issues JWTs with 1–8h TTLs. A removed member's token remains
structurally valid until expiry. We need a server-side deny list checked on every request.

**Design** (`TokenRevocationService.java:21-91`):
- Storage key is `(userId, orgId)` — not the JWT itself — so the same user
  remains valid in their other orgs (relevant for MSP staff and PLATFORM_ADMINs).
- Backing store: `revoked_tokens` Postgres table.
- Hot path: Caffeine `Cache<String, Boolean>` keyed by `"<userId>:<orgId>"`,
  `expireAfterWrite = ttlHours` (default 24h), `maximumSize = 10_000`.
- Warm-up: on `@PostConstruct`, every non-expired row is loaded into the cache
  so a restarting instance does not lose state.
- Read path: `isRevoked(userId, orgId)` checks cache first, then DB; populates
  cache on DB hit.
- Write path: `revokeForUserInOrg(userId, orgId, revokedByUserId, reason)` upserts
  the row and writes the cache.
- Cleanup: `@Scheduled(cron = "0 0 3 * * *")` deletes expired rows nightly.

**Enforcement point**: `JwtAuthenticationFilter.java:119-127`. After the principal
is resolved, if `!platformAdmin && tokenRevocationService.isRevoked(userId, orgId)`,
the filter writes a 401 ProblemDetail and returns. PA sessions are intentionally
exempt so the SaaS operator cannot be denied entry by mass-revocation events.

### 3.2 OrgMember revocation fields

`OrgMember` carries the customer-visible revocation state set in
`TeamService.java:197-201`:

| Field | Set on revoke | Purpose |
|---|---|---|
| `inviteStatus` | `REVOKED` | flips the membership out of `ACCEPTED` |
| `revokedAt` | `Instant.now()` | for UI display + filtering |
| `revokedByUserId` | caller user id | foreign actor for org audit |
| `revokeReason` | free text | shown to ADMIN in member list |

### 3.3 Data model summary

```
org_members
  + revoked_at            timestamptz
  + revoked_by_user_id    uuid
  + revoke_reason         text

invitations
  + cancelled_at          timestamptz
  + cancelled_reason      text

revoked_tokens (new table)
  id                      uuid PK
  user_id                 uuid NOT NULL  -- (user_id, org_id) unique
  org_id                  uuid NOT NULL
  revoked_by_user_id      uuid
  reason                  text
  expires_at              timestamptz NOT NULL
  created_at / updated_at timestamptz

org_audit (new table)
  id                      uuid PK
  org_id                  uuid NOT NULL
  actor_user_id           uuid
  actor_email             varchar
  event_type              varchar        -- e.g. MEMBER_REMOVED
  target_user_id          uuid
  target_email            varchar
  reason                  text
  created_at              timestamptz

platform_admin_audit (new table)
  id                      uuid PK
  acting_user_id          uuid
  acting_user_email       varchar
  target_org_id           uuid
  target_org_name         varchar
  http_method             varchar
  request_path            varchar
  reason                  text
  response_status         int
  created_at              timestamptz
```

### 3.4 Dual audit trail

`TeamService.java:232-247` registers an `afterCommit` synchronisation that fires:

1. **Email notification** to the removed member via `EmailDispatchService.sendMemberRemovedEmail`.
2. **Org audit row** via `OrgAuditService.recordAsync` — visible to the tenant's ADMIN.
3. **Platform-admin audit row** — only when the caller `isPlatformAdmin` — via `PlatformAdminAuditService.recordAsync`.

Why `afterCommit`: the email and audits reference rows that must already exist in the
committed transaction; firing them before commit would race with read-side queries.

## 4. Open follow-ups (not in scope)

- **PA-side revocation**: today only non-PA users can be revoked per the filter at `JwtAuthenticationFilter.java:120`. If a PA is off-boarded, their session keeps working for the remaining JWT TTL. Track separately.
- **Per-JTI revocation**: keying by `(userId, orgId)` means we cannot revoke one device while keeping another. Acceptable for now.
- **No CSV export of removed members**: confirmed product decision.
