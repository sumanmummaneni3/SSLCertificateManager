# Changelog

All notable changes to the CertGuard Cloud platform are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to semantic-ish release tags of the form `vMAJOR.MINOR-topic`.

## [v1.3-invite-fixes] — 2026-05-24

Hardens the end-to-end email invite flow across the auth-service, main API server, and React UI. An architectural audit of the invite path surfaced 13 defects (B1–B13) spanning org-routing correctness, password-login enablement for invited users, OTP brute-force exposure, orphan data growth, link-generation in production deployments, and operational visibility of dev-mode email suppression. All 13 are addressed in this release. No database migration is required; no API contract changes.

Upgrade notes:
- Set `APP_UI_BASE_URL` (or `app.ui-base-url`) to the public origin of the React SPA in every non-local environment. If unset, the value falls back to `app.base-url` to preserve previous behavior.
- On startup the server now logs a WARN banner when `app.dev-mode=true` is in effect — confirm this banner is absent in production logs.

### Fixed

- **B1 — Invited users routed to the wrong organization after their second Google login.** Returning OAuth logins now resolve the user's real organization via their accepted `org_members` row when the user's primary `org_id` does not match any membership, instead of defaulting the session to an empty placeholder workspace with ADMIN privileges. (`AuthProvisioningService`, auth-service)
- **B2 — Invited users could not sign in with email and password.** The "forgot password" endpoint now provisions an `auth_users` record on demand for invited users whose email already exists in the main server database, marking the address as verified (the invite OTP proved ownership) and dispatching the standard password-reset email. Invited users can now set their first password through the normal reset flow. (`EmailAuthService`, auth-service)
- **B3 — Accepting an invitation no longer creates a throwaway organization and subscription.** The invitee's user row is linked directly to the inviting organization, eliminating the steady accumulation of orphan `organizations` and `subscriptions` rows — one pair per invite previously. (`InvitationService`)
- **B4 — Onboarding is no longer prompted for invited users landing in the wrong workspace.** Because invited users now land in the real organization (B3), `onboardingCompleted=true` correctly bypasses the org-setup wizard and drops the user into the inviting org's workspace. (`InvitationService`)
- **B6 — Re-inviting an email no longer leaves multiple valid invitation tokens outstanding.** Issuing a new invite for an address with a pending invitation revokes the prior token immediately; only the most recent link is usable. (`TeamService`, `InvitationRepository`)
- **B7 — Concurrent invite acceptance and OAuth provisioning no longer race to insert the same user.** User creation in the invite path uses an idempotent `INSERT ... ON CONFLICT (email) DO NOTHING` followed by an unconditional re-fetch, so whichever writer commits first wins cleanly and the other path returns the committed row without a constraint violation. (`UserRepository`, `InvitationService`)
- **B8 — Dev-mode email suppression is now loudly visible.** A WARN-level banner is emitted at startup when `app.dev-mode=true`, and each suppressed email is logged at WARN instead of INFO, making it impossible to miss in log aggregators that an environment is silently dropping invitation and OTP messages. (`EmailDispatchService`)
- **B9 — Invitation emails now link to the UI origin, not the API origin.** A new `app.ui-base-url` setting (env: `APP_UI_BASE_URL`) controls the host used in invite links; it defaults to `app.base-url` for backward compatibility and the resolved value is logged at startup so misconfiguration surfaces immediately. Previously, production invite links pointed at the backend port and failed to load the `/invite` route served by the SPA. (`TeamService`, `application.yml`)
- **B11 — Expired invitations are now purged automatically.** A daily scheduled job invokes the existing repository method to delete unused invitations past their expiry, preventing unbounded growth of the `invitations` table. (`InvitationService`)
- **B12 — Invite-flow errors confirmed to be RFC 9457 `ProblemDetail` responses.** Audit verified the existing `GlobalExceptionHandler` mappings (`IllegalArgumentException` → 400, `IllegalStateException` → 409) already cover every exception path raised by the invite endpoints; no code change was needed.
- **B13 — Platform administrators can no longer be invited as organization members.** Attempting to invite an email that already belongs to a `PLATFORM_ADMIN` is rejected with a 400 response, preventing the creation of confusing `org_members` rows for users who already hold system-wide access. (`TeamService`)

### Security

- **B5 — OTP brute-force and unbounded in-memory growth are now prevented.** The in-memory OTP store enforces a maximum of 5 incorrect submissions per invite token before the OTP is invalidated and a fresh validation step is required. A scheduled sweep every 5 minutes evicts expired entries, and expired entries are also evicted eagerly on access. Previously the 6-digit OTP space could be enumerated indefinitely against a single token, and expired records persisted in memory until process restart. (`InvitationService`)
- **B10 — The public invite-validation endpoint can no longer be used to spam OTP emails.** `POST /api/v1/auth/invite/validate` now enforces a 60-second cool-down between resends per invite token and a hard cap of 3 resends per token; each accepted resend issues a fresh OTP with a new 10-minute validity window. Previously, anyone holding the raw invite token could trigger unlimited OTP emails to the invitee and continually overwrite the active OTP. (`InvitationService`)

[v1.3-invite-fixes]: https://github.com/sumanmummaneni3/SSLCertificateManager/releases/tag/v1.3-invite-fixes
[v1.2-microservices]: https://github.com/sumanmummaneni3/SSLCertificateManager/releases/tag/v1.2-microservices
