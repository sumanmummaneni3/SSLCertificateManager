# Runbook — RFC 0015 Phase 3: last_active_org_id backfill

## Purpose

One-time, idempotent rescue of pre-Phase-1 "stuck" multi-org users: home-org
membership missing/revoked, a valid (ACCEPTED, non-revoked) membership elsewhere,
and `last_active_org_id` null or pointing at a non-valid org. Sets `last_active_org_id`
to their most-recent (`created_at DESC`) valid membership — identical to
`ActiveOrgResolver` step 3, so a backfilled user and one who self-heals on next login
converge on the same org. Valid-home-membership users and platform admins are never
touched.

## Mechanism

- Service: `BackfillLastActiveOrgService` (`dryRun` / `apply`).
- Runner: `LastActiveOrgBackfillRunner` (`ApplicationRunner`), gated by
  `APP_BACKFILL_LAST_ACTIVE_ORG_MODE`.
- Modes: `off` (default, no-op) | `dry-run` (identify + log, zero writes) |
  `apply` (single set-based `UPDATE ... RETURNING`, logs each affected row).
- Idempotent: re-running touches 0 rows. Boot-safe: a backfill failure is caught,
  logged ERROR ("retry on next deploy"), and does NOT abort server startup.

## Procedure

1. Deploy with `APP_BACKFILL_LAST_ACTIVE_ORG_MODE=dry-run`. On the VPS use
   `docker compose up -d --force-recreate <server>` (a plain restart does NOT
   re-read env), then verify: `docker compose exec <server> printenv
   APP_BACKFILL_LAST_ACTIVE_ORG_MODE`.
2. Review logs: each line = `(userId, oldLastActiveOrgId, newOrgId)`; summary line
   gives the count ("would be affected"). Confirm the population is the expected
   small pre-Phase-1 set.
3. Deploy with `mode=apply` (`--force-recreate` + `printenv` verify). Logs now read
   "affected" and reflect the actual `UPDATE ... RETURNING` rows.
4. Revert to `mode=off` (`--force-recreate` + `printenv` verify). Idempotency makes a
   forgotten flag harmless, but keep baseline clean.

## Multi-replica note

On an apply deploy across replicas, the runner fires on every replica concurrently.
Safe: the `UPDATE` is atomic/set-based and every replica computes the same
most-recent-DESC target, so writes converge (no ShedLock needed). Expect one "APPLY"
log block per replica — the first logs N rows, the others ~0 once condition (C)
excludes the now-corrected rows.

## Rollback

None needed: the op only fills a previously null/invalid `last_active_org_id` with a
valid membership the user already holds — it never removes access. A user who wants
a different active org uses the Phase-2 switcher.
