# RFC 0002: High-Availability Strategy for CertGuard Cloud

- **Status**: Accepted
- **Date**: 2026-05-15
- **Author**: CertGuard Architect
- **Audience**: Engineering, Ops, Product
- **Related**: `docs/architecture/HLD.md`, `server/docker-compose.yml`

## Context

CertGuard Cloud currently runs as a single-VPS deployment driven by
`server/docker-compose.yml`. The stack contains:

- `postgres` (containerised, local volume `postgres_data`)
- `rabbitmq` (containerised, local volume `rabbitmq_data`)
- `app` — Spring Boot server on `127.0.0.1:8443`
- `gateway` — Spring WebFlux on internal port 8080
- `nginx` — TLS terminator on `:443`
- `prometheus`, `grafana` (observability sidecars)

All persistent state lives on one host. The product is in **single-org SaaS**
mode with no contractual SLA above informal "best-effort uptime". A second VM
with equivalent specs is available and the question is whether to deploy a hot
standby of the full stack now.

## Decision

**Run a single primary VM. Provision the second VM as a _cold standby_, not a
hot standby. Re-evaluate when one of the documented triggers fires.**

The next HA investment after this is **managed Postgres**, not a second app
replica.

## Why "hot standby" today is theater

A second VM running the same compose file only provides real availability if
**all five** of the following are in place. Any missing item reduces the
"standby" to an idle box that does not help during an outage:

1. **Streaming Postgres replication** primary → standby.
   `server/docker-compose.yml:6-25` runs Postgres in a container against a
   local volume; replication is not configured. Without it, failover loses
   every write since the last `pg_dump`.

2. **Floating IP or DNS failover** capable of swinging within the RTO. A
   single A-record with a 60-300s TTL is too slow; managed floating IPs
   (Hetzner, DigitalOcean, OVH) swing in 10-30s.

3. **Shared TLS material** for the public `nginx`. The compose file mounts
   `./certs` (`server/docker-compose.yml:180`). Let's Encrypt certs from one
   box must be synced to the other (rsync cron, or DNS-01 challenge with
   shared storage).

4. **Shared `JWT_SECRET` / `AUTH_JWT_SECRET`**. Both gateway and server
   share this value (`server/docker-compose.yml:70, 151`). Free if `.env`
   is copied to the standby — which is mandatory anyway for DR.

5. **Replicated `AgentCertificateAuthority` keystore**. The BouncyCastle CA
   private key that signs per-agent client certs lives in the server
   keystore volume. Losing it invalidates every issued agent cert. The
   standby must hold this key.

Skipping (1), (2), (3), or (5) makes the standby useless during the failures
that matter most (data loss, DNS not swinging, agents unable to reconnect).

## Why it is not worth it at this stage

- **Compose is single-host bound.** `nginx` binds host `:443`
  (`server/docker-compose.yml:177`). Two VMs running the same compose means
  two independent Postgres clusters diverging unless replication is wired —
  which is real ops work, not a checkbox.

- **The agent CA is irreplaceable and must be backed up regardless.** Once
  encrypted offsite backups exist for (Postgres dump + `.env` + agent CA +
  nginx certs), the realistic RPO is the backup cadence and RTO is "time
  to spin a new VM and restore" — typically 20-40 minutes on Hetzner or
  DigitalOcean. Acceptable for single-org SaaS.

- **Planned downtime already exists.** Each deploy carries ~60-90s of
  downtime (see Phase 4 of the production deploy plan). If that is tolerable
  weekly, it is tolerable for unplanned outages too. A hot standby to dodge
  ~90s/week is overkill.

- **Cost.** Doubling VPS plus a managed Postgres for replication is 2-3x
  infra cost. For one org, that spend is better directed at offsite backups,
  a documented runbook, and a staging environment.

- **Schedulers do not multi-instance safely.** `ScheduledPublicScan`,
  `CertificateExpiryScheduler`, `AgentOfflineScheduler`, and the stale
  claimed-job reset job are not protected by a distributed lock. Two `app`
  containers would double-fire scheduled work — silent duplicate scans and
  duplicate notification emails. Active-active is currently broken by
  construction.

## What to do now, in priority order

1. **Encrypted offsite backups, every 6 hours**, of:
   - `pg_dump` of the production database
   - `/opt/certguard/server/.env`
   - Agent CA keystore
   - `nginx` TLS material

   Tooling: `restic` to S3-compatible storage. Retention: 30 days.
   Result: **RPO ≤ 6h, RTO ≤ 60min** on a clean VM. An untested backup is
   not a backup — **rehearse the restore once per quarter** and record the
   timing in the runbook.

2. **Cold standby VM.** Same spec as primary. Docker installed. Repo cloned
   to `/opt/certguard`. `.env` synced nightly via rsync over SSH. Services
   stopped. Cost on most providers when shut down: near zero. A `docker
   compose up -d` after a `restic restore` of the latest backup is a
   5-minute step. This is the realistic version of "standby" at this stage.

3. **Runbook**: `docs/architecture/runbooks/dr-restore.md` (to be created)
   documents the exact restore procedure: which backup to pull, the order
   to restore volumes, which env vars to verify, how to swing DNS, how to
   verify agent reconnection.

## Triggers for revisiting

Move to the next HA tier when **any one** of these is true:

- **First contractual SLA above 99% uptime is signed** (99% allows ~7h/month
  downtime — the current setup meets this; 99.5% allows ~3.6h/month and is
  the realistic threshold to act on).
- **≥ 5 paying organisations** (compounding blast radius makes a single
  unplanned outage commercially expensive).
- **Single org with > $X ARR** where lost-revenue-per-hour-of-downtime
  exceeds 2x the HA infra delta. Set X with finance.
- **Data loss event** caused by backup-only RPO (validates the case for
  replication regardless of customer count).

## Next HA tier (when a trigger fires)

**Step 1 — Managed Postgres.** Highest leverage. Move the database off the
VM to DigitalOcean Managed DB / Hetzner Postgres / RDS. Benefits:

- Removes the hardest stateful service from the host.
- Point-in-time recovery (PITR) for free.
- Makes a real warm standby of the *app* trivial because the DB is no
  longer a host-local volume.

Migration cost is non-trivial (data export/import, connection string change,
`server/docker-compose.yml:6-25` removed, networking rules). One-time
effort, ~1-2 sprint-days.

**Step 2 — Warm-standby `app` + `gateway`.** Second VM runs `app` and
`gateway` only (Postgres is managed). `nginx` upstream lists both; health
check failover handles cutover.

**Blocker before active-active**: implement a leader-election lock for
the schedulers (advisory `pg_try_advisory_lock` on a well-known key, run
on `@Scheduled` entry). Without this, scheduled jobs double-fire.

**Step 3 — Floating IP + automated DNS failover.** Only after Steps 1 and 2.
Automated failover with no quorum service (no etcd, no consul, no managed
DB) leads to split-brain on Postgres. Step 1 removes that risk.

## Failover mechanism, if a hot standby is ever deployed

**Manual, not automated, until Step 1 above is done.** Single-org SaaS does
not have the traffic to justify automated failover, and without a quorum
service automated failover risks split-brain.

Operational shape:

- External uptime monitoring (UptimeRobot / Better Stack) probes
  `/actuator/health` through public `nginx`.
- Page on two consecutive failures (avoids flapping).
- On-call engineer runs `failover.sh` on the standby:
  1. Stop standby services if running.
  2. Promote replicated Postgres (`pg_ctl promote` or managed-DB promote).
  3. Swing the floating IP to the standby.
  4. `docker compose up -d` on the standby.
  5. Verify agent reconnection via `/api/v1/agents/status`.
- Target: 5 minutes if rehearsed.

## Consequences

- **Accepted risk**: RPO of 6h and RTO of ~60min until triggers fire.
  Documented and signed off.
- **Required work this quarter**: implement "What to do now" items 1, 2, 3.
  Owner: backend-engineer + ops.
- **Deferred work**: managed Postgres migration, warm-standby app,
  scheduler leader-election. Tracked separately; no implementation until
  a trigger fires.
- **Cost**: cold standby VM (shut down most of the time) + S3-compatible
  backup storage ~ $5-15/month total at current scale.

## Open questions

None blocking. Reopen this RFC when a trigger fires.
