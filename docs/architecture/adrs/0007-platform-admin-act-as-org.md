# ADR-0007: Platform-admin act-as-org impersonation

- Status: Accepted
- Date: 2026-05-24
- Deciders: Platform team
- Supersedes: —
- Related: HLD §1 (header trust boundary), RFC-0001 (member offboarding & JWT revocation)

## Context

CertGuard is a multi-tenant SaaS. PLATFORM_ADMIN users (the SaaS operator's own
staff) periodically need to operate inside a customer's organisation to:

- triage a support ticket ("I can't see my certificates"),
- correct broken state after a botched migration,
- demonstrate functionality during onboarding,
- enforce a contractual action (e.g., suspend an org's scanning).

Three options were considered:

1. Give the PA a permanent ADMIN seat in every org. Rejected: pollutes member
   lists, breaks the principle of least privilege, and is invisible in the
   tenant's audit log.
2. Issue a short-lived "ghost" JWT scoped to the target org. Rejected: requires
   re-login flow each switch, hard to revoke mid-session, and inseparable from
   the user's own session for audit purposes.
3. Header-based override on the existing PA session, with mandatory reason on
   writes and a dual audit trail. **Chosen.**

## Decision

We add a request-scoped impersonation contract on the existing JWT auth path.

### Header contract

| Header | Required | Constraint |
|---|---|---|
| `X-Acting-As-Org` | optional | UUID of the target organisation |
| `X-Acting-As-Reason` | required on POST/PUT/PATCH/DELETE | Free-text reason recorded verbatim in audit |

### Enforcement rules (server-side, in `JwtAuthenticationFilter`)

Anchored at `server/src/main/java/com/certguard/security/JwtAuthenticationFilter.java:131-175`:

1. If `X-Acting-As-Org` is present and the caller is **not** PLATFORM_ADMIN →
   `403 Forbidden — X-Acting-As-Org is restricted to PLATFORM_ADMIN`. No further
   processing.
2. If the HTTP method is one of `POST/PUT/PATCH/DELETE` and `X-Acting-As-Reason`
   is blank → `400 Bad Request — X-Acting-As-Reason header is required for write operations`.
3. If the target org UUID does not resolve → `404 Not Found`.
4. Otherwise the filter:
   - Sets `effectiveOrgId = targetOrgId` on the principal.
   - Calls `TenantContext.setHomeOrgId(callerOrgId)` so the caller's true home
     org is preserved for downstream visibility.
   - Synthesises `orgRole = "ADMIN"` so the normal `@PreAuthorize` guards in
     controllers pass without granting the PA org-membership semantics.
   - Adds MDC keys `actingAsOrgId` and `homeOrgId` so every log line on the
     request is correlatable.
5. After the chain runs, the filter unconditionally writes a row via
   `PlatformAdminAuditService.recordAsync` capturing the resolved response
   status code, regardless of success/failure
   (`JwtAuthenticationFilter.java:207-214`,
   `server/src/main/java/com/certguard/service/PlatformAdminAuditService.java:34-56`).

### Dual audit trail

Two audit tables are written for an act-as request that performs a tenant-visible action:

- **Platform-admin audit** (`platform_admin_audit`) — written by the filter on
  every request that carried `X-Acting-As-Org`. Cross-org-visible only to other
  PAs. Records: acting user, target org, HTTP method, path, reason, response
  status. Fire-and-forget `@Async`; failures are logged, never propagated.
- **Org audit** (`org_audit`, written by `OrgAuditService`) — written by the
  domain service when it performs a tenant-visible mutation (member removed,
  role changed, etc.). Visible to the tenant's ADMIN.

### Gateway responsibility

Per HLD §1, the gateway forwards both `X-Acting-As-Org` and `X-Acting-As-Reason`
verbatim. It does **not** strip them — they are not in the `X-CG-*` namespace.
However, the gateway MUST strip every `X-CG-*` header before injecting its own,
because the impersonation logic above runs only after the principal is established
from the trusted `X-CG-*` set; allowing a client to forge `X-CG-Platform-Admin=true`
would bypass the entire gate.

## Consequences

### Positive

- Auditability: every cross-org PA action is captured twice with a mandatory
  human-readable reason on writes.
- No identity confusion: the PA's user_id remains the actor in every domain row;
  only `effectiveOrgId` changes.
- Tenant-visible: the affected org sees the action in their `org_audit` feed.
- Revocable mid-flight: because revocation is keyed by `(userId, orgId)` pairs,
  the PA's home-org JWT is unaffected by revocation in the target org.

### Negative / risks

- **Attack surface on the header-trust boundary.** A misconfigured gateway that
  forwards client-supplied `X-CG-Platform-Admin: true` would allow any user to
  call act-as-org. Mitigation: HLD §1 mandates unconditional gateway strip of
  every `X-CG-*` header before validation; integration test should prove it.
- **Reason fields are free text.** PAs can write "test" and satisfy the guard.
  Mitigation: surface the audit log to the customer's ADMIN UI.
- **No org-side opt-out.** A customer cannot prevent PA impersonation today.
  Acceptable for the current contract; revisit when the SaaS gains a
  "support access" toggle.
- **GET requests are not gated by reason.** Read access without reason is
  intentionally allowed for fast triage, but is still audited.

## Compliance / migration

- New code only; no migration on existing rows.
- Required integration tests: (a) non-PA with header → 403, (b) PA write without
  reason → 400, (c) PA read without reason → 200 with audit row, (d) PA write
  with reason → 2xx + two audit rows when the service emits an org_audit,
  (e) audit row records response status even on 5xx.
- Frontend impact: a PA UI for switching org context. Header injection lives in
  the API client, not in route guards.
