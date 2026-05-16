# Deploy Rollback Runbook

**Owner:** Platform / Backend Engineering
**Last tested:** _(fill in after each rollback drill)_
**Target rollback time:** under 5 minutes

---

## When to use this runbook

Roll back when:

- The GitHub Actions deploy step failed with a non-zero exit (health check timeout, image pull failure, compose error).
- `certguard-app`, `certguard-gateway`, or `certguard-ui` is unhealthy after a deploy.
- Production error rate spiked immediately after a deploy (check Grafana or application logs).
- A critical bug is confirmed in the newly deployed version and the fix is not immediately available.

**Do not roll back** for issues unrelated to the most recent deploy (e.g., a downstream third-party API outage, DNS issue, or a pre-existing bug that was not introduced by the current deploy).

---

## Fast rollback path (target: under 5 minutes)

SSH into the VPS and run:

```bash
bash /opt/certguard/scripts/vps-rollback.sh <PREVIOUS_TAG>
```

The script will:
1. Write the previous tag to a temporary `.env.deploy` file.
2. Pull the previous images from GHCR.
3. Run `docker compose up -d --no-build` to replace the running containers.
4. Wait up to 120 seconds for `certguard-app` to become healthy.
5. Remove the temporary file and print confirmation.

---

## How to find the previous tag

**Option A — from local Docker image cache on the VPS:**

```bash
# SSH into the VPS first
docker images ghcr.io/<GITHUB_REPOSITORY_OWNER>/certguard-app --format "{{.Tag}}"
```

This lists all tags that have been pulled to the VPS. The previous deploy's SHA will be visible if the VPS has not yet garbage-collected it.

**Option B — from GitHub Actions deploy run history:**

Go to the repository on GitHub, click on **Actions**, find the **Deploy to VPS** workflow, and look at the run before the failed one. The image tag is logged in the "Determine image tag" step output.

**Option C — from git log:**

```bash
# On the VPS or locally
git log --oneline origin/main | head -20
```

Each commit SHA corresponds to a Docker image tag that was built by the build-and-publish workflow.

---

## Database rollback caveat

Flyway migrations are **forward-only**. When you roll back the app image, the database schema is NOT automatically reverted.

### When you do NOT need to restore the database (most cases)

Additive migrations — adding a table, adding a nullable column, adding an index — are backward-compatible with the previous app version. The old app image simply ignores the new columns or tables. In this case:

1. Roll back the app image with `vps-rollback.sh`.
2. The old app starts normally. Flyway detects that the migration has already been applied and skips it.
3. No database action required.

### When you DO need to restore the database (rare, destructive migrations)

If the migration that ran during the failed deploy was **destructive** — dropped a column, dropped a table, renamed a column the old code depends on — the old app image will fail to start against the new schema.

In this case you must restore from the pg_dump taken by `vps-deploy.sh` immediately before the deploy:

```bash
# On the VPS
cd /opt/certguard
set -o allexport; source .env; set +o allexport

# Find the pre-deploy backup (it will be the most recent one)
ls -lt backups/pgdump-*.sql.gz | head -5

# Restore (this DROPS and RECREATES the database state from the backup)
# WARNING: any data written between the backup and now will be lost.
zcat backups/pgdump-YYYYMMDD-HHMMSS.sql.gz \
  | docker exec -i certguard-postgres psql -U "${POSTGRES_USER}" "${POSTGRES_DB}"
```

After restoring the database, run the image rollback:

```bash
bash /opt/certguard/scripts/vps-rollback.sh <PREVIOUS_TAG>
```

### How to check whether the migration was destructive

Before deciding to restore the DB, review the migration that ran. Flyway logs the migration filename at startup:

```bash
docker logs certguard-app 2>&1 | grep "Flyway"
```

Then inspect the migration file in the repo:

```bash
cat server/src/main/resources/db/migration/V<N>__<description>.sql
```

If the migration only adds objects (CREATE TABLE, ADD COLUMN, CREATE INDEX), no DB restore is needed.

---

## Post-rollback checklist

- [ ] `certguard-app` is healthy: `docker inspect --format='{{.State.Health.Status}}' certguard-app`
- [ ] Login via the UI works.
- [ ] Core functionality verified (view certificates, trigger a scan).
- [ ] Filed a GitHub issue describing what failed and linking to the failed Actions run.
- [ ] Pinned `APP_IMAGE_TAG=<PREVIOUS_TAG>` in `/opt/certguard/.env` to prevent the next auto-deploy from re-deploying the broken image.
- [ ] Notified the team that a broken build is pinned and the deployment workflow is temporarily blocked.

---

## Pinning the image tag after rollback

To prevent the automatic deploy workflow from re-deploying the bad image immediately after the next push to main, set the tag in `.env` on the VPS:

```bash
# On the VPS — edit .env and set:
APP_IMAGE_TAG=<PREVIOUS_TAG>
```

Then update the deploy workflow input when you are ready to re-deploy the fixed version by using the **workflow_dispatch** trigger with the new image tag.

---

## When NOT to roll back the image

- **Transient infrastructure issue** (cloud provider network blip, DNS timeout): wait and monitor before rolling back.
- **Configuration error in `.env`** (wrong credential, missing variable): fix `.env` and restart the container — no image rollback needed.
- **Agent connectivity issue after a deploy**: agents reconnect automatically on the next poll cycle. Wait 60–90 seconds before deciding the rollback is warranted.
