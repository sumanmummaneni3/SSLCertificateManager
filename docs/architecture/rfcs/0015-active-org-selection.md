# RFC 0015 — Active organization selection for multi-org users

- **Status:** Proposed (docs only; no implementation started)
- **Date:** 2026-07-24
- **Owners:** Architect (design) / Backend + Auth-service + Frontend (implementation)
- **Scope:** CertGuard Cloud server (`server/`), auth-service (`certguard-auth-service/`), UI (`ui/`)
- **Related code:**
  - `server/src/main/java/com/certguard/entity/User.java:16-18`
  - `server/src/main/java/com/certguard/repository/UserRepository.java:27-37`
  - `server/src/main/java/com/certguard/service/InvitationService.java:159-198`
  - `server/src/main/java/com/certguard/config/OAuth2AuthenticationSuccessHandler.java:145-156`
  - `server/src/main/java/com/certguard/security/JwtAuthenticationFilter.java:180`
  - `server/src/main/java/com/certguard/security/TenantContext.java:12-13`
  - `server/src/main/java/com/certguard/service/TargetService.java:52-53`
  - `server/src/main/java/com/certguard/controller/OrgController.java:32-36`
  - `server/src/main/java/com/certguard/controller/MeController.java:53-63`
  - `server/src/main/java/com/certguard/entity/OrgMember.java`
  - `server/src/main/java/com/certguard/security/JwtTokenProvider.java:22,33-56`
  - `certguard-auth-service/src/main/java/com/certguard/auth/service/AuthProvisioningService.java:34-69`
- **Relates to:** RFC 0001 (member offboarding / revocation — the revocation machinery this design must respect), GAPS N16 / R26.

## 1. Problem

An invited team member, after accepting an invite into org B, sees the wrong
organization and none of org B's targets on every login *after* the one-time
invite-accept session.

### 1.1 Reported symptom

A user who already has any account (self-signed-up with their own
auto-provisioned org A, or previously accepted a different invite) is then
invited into org B. They see org B correctly **only** during the single
OTP-accept session immediately after clicking the invite link. The moment they
log out and sign back in normally via Google, they are put back into org A —
wrong org name displayed, and none of org B's targets visible. There is no
org-switcher anywhere to let them self-correct.

### 1.2 Root cause (verified against source)

1. `User.organization` is a single, non-nullable `@ManyToOne` "home org" FK, set
   once at row creation and never changed (`User.java:16-18`). There is no
   "currently active org" stored anywhere on the user.

2. `UserRepository.insertIfAbsent` is `INSERT ... ON CONFLICT (email) DO NOTHING`
   (`UserRepository.java:27-37`). It sets `org_id` only when the row is first
   created; re-invoking it for an existing user is a no-op — `org_id` never
   updates.

3. `InvitationService.acceptInvite` (`InvitationService.java:159-198`) correctly
   scopes the OTP-accept session: it creates/reactivates an `OrgMember` for the
   invited org and issues a JWT with `orgId = org.getId()`. **This single session
   is correct.** The bug is what happens on every subsequent login.

4. `OAuth2AuthenticationSuccessHandler` — the normal "Sign in with Google" path —
   always mints the JWT with `user.getOrganization().getId()`
   (`OAuth2AuthenticationSuccessHandler.java:148-156`), i.e. the fixed home-org
   FK. It never consults the user's other `OrgMember` rows. (It reads OrgMember
   only to resolve the role *for the home org*, defaulting to `"ADMIN"` if none
   exists — a latent privilege bug, see §6.4.)

5. Downstream, `JwtAuthenticationFilter.doFilterInternal` sets
   `TenantContext.setOrgId(effectiveOrgId)` straight from the JWT `orgId`
   (`JwtAuthenticationFilter.java:180`). Every tenant-scoped read is scoped to it:
   `TargetService.listTargets(orgId,…)` (`TargetService.java:52-53`),
   `OrgController.getOrg(TenantContext.getOrgId())` (`OrgController.java:32-36`).
   So the wrong (home) org is displayed and org B's targets are invisible.

### 1.3 The data model is not the gap

`OrgMember` (`entity/OrgMember.java`) is already a complete multi-membership
join table: unique `(org_id, user_id)`, `inviteStatus`, `revokedAt`,
`createdAt` via `BaseEntity`. `MeController.java:53-63` already enumerates all of
a user's memberships (`findAllByUserId`) into a `memberships` list. The model and
the `/me` API already anticipate multi-org membership. **The gap is purely which
org's JWT gets minted at login time, and the absence of any switch mechanism.**

This is distinct from the MSP `accessibleOrgIds` mechanism
(`JwtAuthenticationFilter.java:184-192`, `TenantContext.getAccessibleOrgIds`),
which is a one-directional parent→child hierarchy for MSP orgs — not arbitrary
multi-membership via `OrgMember`. This RFC does not touch that mechanism.

## 2. Two issuance paths — both affected, differently

There are two JWT-issuance paths, and **both reproduce the bug**:

- **Server-local (HS256)** — `OAuth2AuthenticationSuccessHandler` blindly uses the
  home-org FK. No fallback at all.
- **Auth-service (RS256)** — `AuthProvisioningService.resolveOrProvision`
  (`AuthProvisioningService.java:34-69`) reads `users.org_id`, and if an ACCEPTED
  `OrgMember` exists in that home org (which every self-signup user has), it uses
  the home org (lines 47-52) → **bug reproduces identically**. It only falls back
  to "oldest accepted membership across all orgs" (lines 56-61) when the home org
  has *no* membership (the pure-placeholder-invite case), and even then picks
  *oldest*, not the invited org.

Consequence: a fix that touches only `OAuth2AuthenticationSuccessHandler` fixes
local/dev but leaves production broken if prod runs the gateway/auth-service
topology. **Which path is live in prod is currently unresolved** (see GAPS §7 and
N1). Both paths must be fixed and kept in lockstep. See §7 Open Question 1.

## 3. Design: `last_active_org_id` + explicit switch

### 3.1 Persisted active org

Add a nullable `users.last_active_org_id UUID REFERENCES organizations(id)`.
Nullable is deliberate: it is additive, zero-downtime, and lets resolution fall
back to today's behavior for rows that predate the column.

### 3.2 Resolution rule (single source of truth)

Given a `userId`, resolve `(effectiveOrgId, orgRole)` as:

1. `candidate = users.last_active_org_id`.
2. `candidate` is **valid** iff an `OrgMember(user, candidate)` exists with
   `inviteStatus = ACCEPTED` and `revokedAt IS NULL`. If valid → use `candidate`
   and *that membership's* role.
3. Else apply the default policy (see §7 Open Question 2). Recommended default:
   home org (`users.org_id`) if it has a valid membership, else the most-recent
   accepted non-revoked membership (`ORDER BY created_at DESC`).
4. If a valid membership was chosen, resolve `orgRole` from **that** membership —
   never blindly default to `ADMIN` (fixes §6.4).
5. Persist the resolved org back to `last_active_org_id` if it changed (keeps the
   choice sticky across logins).

This rule is implemented once server-side (`ActiveOrgResolver`) and mirrored in
the auth-service's `JdbcTemplate` SQL — the two must stay identical.

### 3.3 Stamp on invite accept

`InvitationService.acceptInvite` sets `user.last_active_org_id = invited org` in
the same transaction as the membership write. This is the single change that
fixes the reported scenario end-to-end: the invited org becomes sticky, so the
next normal login lands there regardless of the null-default policy.

## 4. Dual-issuance-path / signing-ownership constraint

The server signs **HS256** (`JwtTokenProvider.java:33-56`). The gateway validates
**RS256** and injects `X-CG-*` identity headers; the server trusts those headers
(`JwtAuthenticationFilter.java:66-117`). Therefore:

- A server-side switch endpoint that re-mints an **HS256** token is **rejected by
  the gateway** (fails RS256 validation → gateway 401). It only works on the
  bare-JWT local/dev fallback (`JwtAuthenticationFilter.java:77-84`).
- The **authoritative** switch endpoint must live in the **auth-service**, which
  owns the RS256 signing key. The server switch endpoint is the local/dev
  equivalent only.

This signing-ownership rule is the central architectural constraint of this RFC:
**org context is minted where the signing key lives.** The server may *persist*
`last_active_org_id` and validate membership, but it cannot mint a token the
gateway will accept.

## 5. Phased plan

### Phase 0 — Decisions before coding (see §7)
Resolve the three open questions (live path, default policy, backfill) first.

### Phase 1 — Minimal, backward-compatible fix
1. Migration `V45__add_last_active_org_id_to_users.sql` (verify next `Vn`;
   additive nullable column + FK; no `CREATE INDEX CONCURRENTLY`).
2. Map `lastActiveOrgId` on `User` (store the UUID, not an association).
3. `OrgMemberRepository`: add
   `findFirstByUserIdAndInviteStatusAndRevokedAtIsNullOrderByCreatedAtDesc` and a
   membership-validation lookup.
4. New `ActiveOrgResolver` service implementing §3.2.
5. `InvitationService.acceptInvite`: stamp `last_active_org_id = invited org`
   (§3.3).
6. `OAuth2AuthenticationSuccessHandler`: replace `user.getOrganization().getId()`
   + role lookup with `ActiveOrgResolver`.
7. `AuthProvisioningService`: read/validate `last_active_org_id` before falling
   back to `users.org_id`, mirroring §3.2 in SQL.

End state: any invite acceptance is sticky on every future login; single-org
users unchanged; no JWT format change; already-issued tokens keep working until
natural 8h expiry.

### Phase 2 — Explicit, switchable org context
1. Auth-service `POST /api/auth/switch-org {orgId}` (authoritative, RS256):
   validate ACCEPTED non-revoked membership; reject if
   `TokenRevocationService`-revoked; update `last_active_org_id`; re-mint RS256
   token with new org + resolved role.
2. Server `POST /api/v1/auth/switch-org` (local/dev, HS256): same validation +
   persistence; document that this token is not accepted behind the gateway.
3. UI org switcher: driven by existing `/api/v1/auth/me` `memberships`
   (`MeController.java:97-98`); calls switch-org, swaps the stored token, refetches
   `/me` + dashboard. Net-new (`ui/src` has no `switchOrg`/`activeOrg` today).
4. Gateway: switch-org is authenticated user traffic; no `PUBLIC_PATTERNS` entry
   needed — confirm it is not accidentally blocked.

### Phase 3 — Backfill for already-stuck users
No blanket backfill. Targeted, safe rescue only: set `last_active_org_id` to the
most-recent accepted non-revoked membership **only** for users whose home-org
membership is missing/revoked but who have another membership (unsticks
pure-invite users without disturbing single-org accounts). Users with a valid
home membership self-correct via the Phase 2 switcher.

### Phase 4 — Tests
- Unit `ActiveOrgResolverTest`: home-valid, home-invalid→most-recent, sticky
  `last_active`, `last_active` pointing at a revoked membership → fallback, role
  resolved from the correct membership (never blind ADMIN).
- Testcontainers regression for the exact bug: user with home org A + accepted
  membership in org B; drive the OAuth2 handler and assert the minted JWT `orgId`
  == B after accept-stamp; assert `TargetService.listTargets` returns B's targets.
- Integration: `acceptInvite` stamps `last_active_org_id`; switch-org happy path;
  non-member rejected; revoked-org rejected.
- Auth-service: mirror the resolver test against `AuthProvisioningService`.
- Validate `V45` against a real Postgres — per GAPS R24 the `tctest` profile runs
  `ddl-auto: create-drop` and does not exercise Flyway DDL.

## 6. Risk / rollout notes

1. **Blast radius**: login + tenant scoping touch every authenticated request.
   Mitigation: additive nullable column; resolution defaults to today's behavior
   when `last_active_org_id` is null, so partial rollout degrades gracefully.
2. **Already-issued JWTs** (8h TTL, `expiration-ms:28800000`,
   `JwtTokenProvider.java:22`) keep their old `orgId` until expiry — acceptable;
   self-heals within 8h; no forced revocation needed.
3. **Both paths must ship together** (Phase 1 items 6 and 7). Shipping only the
   server-local fix while prod runs the gateway/auth-service topology leaves prod
   broken — this is why Open Question 1 gates the work.
4. **Privilege tightening bundled in**: today a user whose home-org membership is
   missing is silently granted `ADMIN` at login
   (`OAuth2AuthenticationSuccessHandler.java:151`). §3.2 step 4 removes that.

## 7. Open questions (Phase 0 — must be answered before Phase 2)

1. **Which JWT-issuance path is live in prod** — server-local HS256 or
   auth-service RS256? If unknown or both, the fix ships to both paths. Do not
   assume. (Cross-refs GAPS N1 / §7 "which JWT path is authoritative".)
2. **Default active-org policy when `last_active_org_id` is null** (predates the
   column / never switched). Recommended: home org if it has a valid membership,
   else most-recent accepted membership. Confirm before Phase 2, as it changes
   behavior for users who legitimately prefer their home org.
3. **Backfill already-stuck users?** Recommended targeted rescue (Phase 3), not
   blanket. Confirm scope and whether any user intent can be inferred beyond
   "most-recent membership".
