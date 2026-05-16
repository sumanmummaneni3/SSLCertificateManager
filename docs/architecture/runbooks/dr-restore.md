# Disaster Recovery Restore Runbook

**Owner:** Platform / Backend Engineering
**Last tested:** _(fill in after each DR drill)_
**Estimated restore time:** 30–60 minutes (excluding DNS propagation)

---

## When to use this runbook

Use this runbook only when the primary VPS is **unrecoverable** — hardware failure, provider incident, or accidental deletion. For a bad deploy, use the rollback runbook (`rollback.md`) instead.

This is the last-resort path: provision a clean VM and restore CertGuard from backup.

---

## Prerequisites

Before starting, confirm you have:

- [ ] A clean VM (Hetzner CX21 / DigitalOcean Droplet or equivalent) with Ubuntu 22.04 or 24.04.
- [ ] Docker Engine (v25+) and the docker compose v2 plugin installed on the new VM.
- [ ] Git installed on the new VM.
- [ ] Access to at least one of:
  - The hosting provider's VM snapshot/backup (fastest path — restores the full disk)
  - The latest `pg_dump` backup (from `/opt/certguard/backups/pgdump-*.sql.gz` on the old VM or offsite storage)
- [ ] The `.env` file contents (from a secure password manager or offsite encrypted backup).
- [ ] The `server/certs/` directory contents (from `/opt/certguard/backups/certs-*/` or offsite backup).
- [ ] A GitHub Personal Access Token (PAT) with `read:packages` scope for pulling from GHCR if the packages are private.
- [ ] The last known-good image tag (check GitHub Actions deploy run history).

---

## Step-by-step restore procedure

### Step 1 — Provision a new VM

Provision a VM with the same size as the original (minimum 2 vCPU / 4 GB RAM). Record its IP address.

Install Docker and git:

```bash
# On the new VM (as root or with sudo)
apt-get update && apt-get install -y git curl

# Install Docker Engine (official script — review before running in production)
curl -fsSL https://get.docker.com | sh

# Add your deploy user to the docker group
usermod -aG docker <DEPLOY_USER>
newgrp docker
```

### Step 2 — Clone the repository

```bash
git clone https://github.com/<OWNER>/SSLCertificateManager.git /opt/certguard
cd /opt/certguard
```

### Step 3 — Restore `.env`

Copy `.env` from your secure store to `/opt/certguard/.env`. This file contains all secrets (database passwords, JWT secret, SMTP credentials, etc.) and must never be committed to the repository.

```bash
# Example: copy from a local machine via scp
scp /path/to/certguard.env <DEPLOY_USER>@<NEW_VM_IP>:/opt/certguard/.env
```

Verify the file is not world-readable:

```bash
chmod 600 /opt/certguard/.env
```

### Step 4 — Restore `server/certs/`

The `server/certs/` directory contains the Agent Certificate Authority keystore. Without it, enrolled agents cannot authenticate and new agents cannot be issued client certificates. This data is **irreplaceable** — do not skip this step.

```bash
# Option A: from a pre-deploy backup tar (recommended)
tar -xzf /path/to/certs-backup.tar.gz -C /opt/certguard/server/

# Option B: from a time-stamped directory backup
cp -r /path/to/backups/certs-YYYYMMDD-HHMMSS /opt/certguard/server/certs
```

### Step 5 — Restore Postgres data

Start the database container first (without the app, so Flyway does not run yet):

```bash
cd /opt/certguard
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d postgres
```

Wait for Postgres to become healthy:

```bash
docker compose ps   # watch until postgres is "healthy"
```

Restore from the pg_dump backup. Source `.env` first to get `POSTGRES_USER` and `POSTGRES_DB`:

```bash
set -o allexport; source .env; set +o allexport

# Replace with the path to your latest backup
DUMP_FILE="/opt/certguard/backups/pgdump-YYYYMMDD-HHMMSS.sql.gz"

zcat "${DUMP_FILE}" | docker exec -i certguard-postgres psql -U "${POSTGRES_USER}" "${POSTGRES_DB}"
```

If the `pg_dump` backup is stored offsite (S3, Backblaze, etc.), download it first:

```bash
# Example: download from S3
aws s3 cp s3://your-backup-bucket/certguard/pgdump-latest.sql.gz /opt/certguard/backups/
```

### Step 6 — Set the image tag

Determine the last known-good image tag from the GitHub Actions deploy run history, then set it in `.env`:

```bash
# Edit .env and set or update the following line:
# APP_IMAGE_TAG=<last-known-good-sha>
#
# Example:
echo "APP_IMAGE_TAG=abc1234" >> /opt/certguard/.env
```

Also ensure `GITHUB_REPOSITORY_OWNER` is set in `.env` (this is the GitHub username or org that owns the GHCR packages).

### Step 7 — Pull images from GHCR

If the GHCR packages are private, authenticate first:

```bash
echo "<YOUR_PAT>" | docker login ghcr.io -u <GITHUB_USERNAME> --password-stdin
```

Pull the images:

```bash
cd /opt/certguard
set -o allexport; source .env; set +o allexport

docker compose -f docker-compose.yml -f docker-compose.prod.yml pull app gateway ui
```

### Step 8 — Start all services

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-build
```

Flyway migrations will run automatically when the `app` container starts. If the pg_dump was taken from the same app version, no new migrations will run.

### Step 9 — Verify health

```bash
docker compose ps
```

All services should show `healthy` within 2–3 minutes. Check specific services:

```bash
docker inspect --format='{{.State.Health.Status}}' certguard-app
docker inspect --format='{{.State.Health.Status}}' certguard-gateway
docker inspect --format='{{.State.Health.Status}}' certguard-ui
```

If any container is unhealthy, inspect its logs:

```bash
docker logs certguard-app --tail 100
```

### Step 10 — Swing DNS to the new VM

Update the DNS A record for your domain to point to the new VM's IP address. TTL changes propagate in 1–60 minutes depending on your registrar and the previous TTL setting.

```
Old IP: <OLD_VM_IP>  -->  New IP: <NEW_VM_IP>
```

While DNS propagates, verify the new VM responds correctly by testing directly against its IP:

```bash
curl -k https://<NEW_VM_IP>/actuator/health
```

### Step 11 — Verify agent reconnection

Once DNS has propagated, confirm that scanning agents have re-established their connection to the server:

```bash
# Using a valid JWT or API key:
curl -H "Authorization: Bearer <TOKEN>" \
  https://<YOUR_DOMAIN>/api/v1/agents/status
```

All previously enrolled agents should appear with a recent `last_seen_at` timestamp. If agents show as offline, they reconnect automatically on their next poll cycle (default: every 30 seconds). No manual action is required on agent hosts unless the server TLS certificate changed.

### Step 12 — Update this runbook

After completing the restore, record the following at the top of this runbook:

- Date and time of the incident
- Duration of the restore process
- Any steps that needed improvisation
- Issues found and improvements to make

---

## Post-restore checklist

- [ ] All containers show `healthy` in `docker compose ps`
- [ ] Login via the UI works
- [ ] At least one certificate scan runs successfully
- [ ] Agents reconnect (check `GET /api/v1/agents/status`)
- [ ] DNS points to the new VM
- [ ] SSL certificate for the domain is valid (Let's Encrypt / nginx)
- [ ] Monitoring (Prometheus + Grafana) shows data
- [ ] Old VM decommissioned or snapshots deleted to avoid ongoing charges
- [ ] Runbook updated with restore timing
