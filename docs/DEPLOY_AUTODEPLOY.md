# Pull-based auto-deploy (private-network VPS)

The production VPS sits on a private network with **no inbound SSH**, so GitHub
Actions cannot push a deploy to it. Instead the VPS reaches **out** to GHCR on a
schedule, detects new images, and runs the existing deploy script.

```
GitHub Actions (build-and-publish.yml)        VPS (private network)
  build + push images to GHCR  ───────────►  GHCR
                                               ▲ (outbound poll every 5 min)
                                               │
                              certguard-autodeploy.timer
                                               │ runs
                                      scripts/vps-autodeploy.sh
                                               │ digest changed?
                                               ▼ yes
                                      scripts/vps-deploy.sh latest --no-deps
                                  (pg_dump • keystore backup • git pull •
                                   force-recreate • health checks • nginx)
```

## Why a poller instead of Watchtower's auto-update

[Watchtower](https://containrrr.dev/watchtower/) updates containers by swapping
the image and recreating the single container. Our deploy is **multi-step host
orchestration** that lives in `scripts/vps-deploy.sh`: a Postgres backup, a
keystore backup, a `git pull` of the compose files, a coordinated
force-recreate of app + gateway + auth-service, health gating, and an nginx
restart. Watchtower cannot run those host-level steps, so we keep `vps-deploy.sh`
as the executor and add only the missing piece — a **pull trigger**:

- `scripts/vps-autodeploy.sh` polls the `:latest` digest of the three images
  (`certguard-app`, `certguard-gateway`, `certguard-auth-service`). If any digest
  differs from the last deployed set (tracked in `/opt/certguard/.autodeploy-state`),
  it calls `vps-deploy.sh latest --no-deps`.
- `scripts/systemd/certguard-autodeploy.{service,timer}` run it every 5 minutes.

This is the "timer checks GHCR and redeploys" model. Trade-off vs the old
SSH-push job: it tracks the `:latest` tag, so deploys are **not pinned to a
specific commit SHA** — whatever `:latest` points to is what ships.

## Install (run once on the VPS)

Assumes the repo is checked out at `/opt/certguard` and `.env` is populated with
`GITHUB_REPOSITORY_OWNER` and `GITHUB_PAT` (read:packages) — the same values
`vps-deploy.sh` already uses.

```bash
cd /opt/certguard
git pull --ff-only

# Make the scripts executable
chmod +x scripts/vps-autodeploy.sh scripts/vps-deploy.sh

# Install the systemd units
sudo cp scripts/systemd/certguard-autodeploy.service /etc/systemd/system/
sudo cp scripts/systemd/certguard-autodeploy.timer   /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now certguard-autodeploy.timer

# Verify
systemctl status certguard-autodeploy.timer
systemctl list-timers certguard-autodeploy.timer
```

The **first** poller run seeds `/opt/certguard/.autodeploy-state` with the
current digests and does **not** deploy (the box is assumed to be on `:latest`
at install time). Subsequent runs deploy whenever a new image is published.

## Operating it

```bash
# Run a check immediately (don't wait for the timer)
sudo systemctl start certguard-autodeploy.service

# Watch what it did
journalctl -u certguard-autodeploy.service -f

# Force a deploy on the next tick (re-baseline)
sudo rm /opt/certguard/.autodeploy-state

# Pause / resume auto-deploy
sudo systemctl disable --now certguard-autodeploy.timer
sudo systemctl enable  --now certguard-autodeploy.timer
```

If a deploy fails, the state file is **left unchanged** so the next tick retries.
Roll back manually with `scripts/vps-rollback.sh`.

## Tuning

- **Poll interval** — edit `OnUnitActiveSec=5min` in the timer unit, then
  `sudo systemctl daemon-reload && sudo systemctl restart certguard-autodeploy.timer`.
- **Pin to a release tag instead of `:latest`** — set `IMAGE_TAG=v1.2.0` in the
  service unit (`Environment=IMAGE_TAG=v1.2.0`); the poller and `vps-deploy.sh`
  will both use it.
- **Custom checkout path** — set `Environment=DEPLOY_PATH=/srv/certguard` in the
  service unit.

## Optional: Watchtower for notifications only

If you also want a heads-up when a new image is available (without letting it
deploy), run Watchtower in **monitor-only** mode alongside the poller. It will
not touch containers — it only sends a notification. Add to your prod compose:

```yaml
  watchtower:
    image: containrrr/watchtower
    container_name: certguard-watchtower
    restart: unless-stopped
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - ${HOME}/.docker/config.json:/config.json:ro   # GHCR auth for private images
    environment:
      WATCHTOWER_MONITOR_ONLY: "true"        # never update — the poller deploys
      WATCHTOWER_POLL_INTERVAL: "300"
      WATCHTOWER_NOTIFICATIONS: "shoutrrr"
      WATCHTOWER_NOTIFICATION_URL: "<shoutrrr-url>"   # e.g. slack/telegram/email
```

The actual deploy stays with `certguard-autodeploy.timer`; Watchtower here is
purely an alerting convenience.
