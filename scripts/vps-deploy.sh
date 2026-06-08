#!/usr/bin/env bash
# vps-deploy.sh — Deploy a specific image tag to the production VPS.
#
# NOTE: After cloning the repo to the VPS for the first time, make this script executable:
#   chmod +x /opt/certguard/scripts/vps-deploy.sh
#
# Usage:
#   bash /opt/certguard/scripts/vps-deploy.sh [OPTIONS] [IMAGE_TAG]
#
# IMAGE_TAG: The Docker image tag to deploy (SHA or version string, e.g. abc1234 or v1.2.0).
#            Defaults to "latest" if not supplied.
#
# Options:
#   --no-deps   Pass --no-deps to docker compose up, skipping recreation of
#               dependency containers (postgres, rabbitmq). Use this when those
#               containers are already running and must not be restarted.
#
# Examples:
#   bash scripts/vps-deploy.sh latest --no-deps
#   bash scripts/vps-deploy.sh v1.2.0
#   bash scripts/vps-deploy.sh --no-deps
#
# Environment variables (read from .env):
#   POSTGRES_USER           — Postgres superuser for pg_dump
#   POSTGRES_DB             — Database name for pg_dump
#   GITHUB_REPOSITORY_OWNER — Owner of the GHCR packages
#   GITHUB_PAT              — Personal Access Token with read:packages scope
#
# The script writes a temporary .env.deploy file to pass APP_IMAGE_TAG without
# modifying the canonical .env file. It is cleaned up on completion.

set -euo pipefail

# ── Argument parsing ──────────────────────────────────────────────────────────
IMAGE_TAG="latest"
NO_DEPS=""
for arg in "$@"; do
  case "$arg" in
    --no-deps) NO_DEPS="--no-deps" ;;
    *)         IMAGE_TAG="$arg" ;;
  esac
done

# ── Configuration ────────────────────────────────────────────────────────────
DEPLOY_PATH="${DEPLOY_PATH:-/opt/certguard}"
COMPOSE_BASE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"
AUTH_COMPOSE_BASE="docker compose -f certguard-auth-service/docker-compose.yml -f certguard-auth-service/docker-compose.prod.yml"
ENV_DEPLOY=".env.deploy"
HEALTH_APP_TIMEOUT=120
HEALTH_GATEWAY_TIMEOUT=60
HEALTH_AUTH_TIMEOUT=90
# ─────────────────────────────────────────────────────────────────────────────

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

wait_healthy() {
  local container="$1"
  local timeout="$2"
  local elapsed=0
  log "Waiting up to ${timeout}s for ${container} to become healthy..."
  while true; do
    local status
    status=$(docker inspect --format='{{.State.Health.Status}}' "${container}" 2>/dev/null || echo "missing")
    if [ "${status}" = "healthy" ]; then
      log "${container} is healthy."
      return 0
    fi
    if [ "${elapsed}" -ge "${timeout}" ]; then
      log "ERROR: ${container} did not become healthy within ${timeout}s (last status: ${status})."
      log "Check container logs with: docker logs ${container}"
      return 1
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
}

# ── 1. Move to deploy path ────────────────────────────────────────────────────
cd "${DEPLOY_PATH}"
log "Working directory: $(pwd)"
log "Deploying image tag: ${IMAGE_TAG}"

# ── 2. Source .env for credentials ───────────────────────────────────────────
if [ ! -f .env ]; then
  log "ERROR: .env file not found at ${DEPLOY_PATH}/.env. Cannot proceed."
  exit 1
fi
set -o allexport
# shellcheck disable=SC1091
source .env
set +o allexport

# ── 3. Postgres backup ───────────────────────────────────────────────────────
mkdir -p backups
PGDUMP_FILE="backups/pgdump-$(date +%Y%m%d-%H%M%S).sql.gz"
log "Starting pg_dump backup -> ${PGDUMP_FILE}"
docker exec certguard-postgres pg_dump -U "${POSTGRES_USER}" "${POSTGRES_DB}" \
  | gzip > "${PGDUMP_FILE}"
log "Postgres backup complete: ${PGDUMP_FILE}"

# ── 4. Keystore backup ───────────────────────────────────────────────────────
CERTS_BACKUP="backups/certs-$(date +%Y%m%d-%H%M%S)"
log "Backing up server/certs -> ${CERTS_BACKUP}"
cp -r server/certs "${CERTS_BACKUP}" 2>/dev/null \
  || log "WARNING: server/certs not found or copy failed — skipping keystore backup."

# ── 5. git pull ───────────────────────────────────────────────────────────────
log "Pulling latest compose files..."
git pull --ff-only
log "git pull complete."

# ── 6. Write image tag to .env.deploy ────────────────────────────────────────
# Never overwrite .env — write a throwaway override file instead.
printf 'APP_IMAGE_TAG=%s\n' "${IMAGE_TAG}" > "${ENV_DEPLOY}"
log "Image tag written to ${ENV_DEPLOY}."

# ── 7. Authenticate to GHCR ──────────────────────────────────────────────────
log "Authenticating to GHCR..."
if [ -z "${GITHUB_PAT:-}" ]; then
  log "ERROR: GITHUB_PAT is not set in .env. Cannot authenticate to GHCR."
  exit 1
fi
OWNER="${GITHUB_REPOSITORY_OWNER}"
echo "${GITHUB_PAT}" | docker login ghcr.io -u "${OWNER}" --password-stdin
log "GHCR authentication successful."

# ── 8. Pull images from GHCR ─────────────────────────────────────────────────
log "Pulling images for tag ${IMAGE_TAG}..."
${COMPOSE_BASE} --env-file .env --env-file "${ENV_DEPLOY}" pull app gateway
${AUTH_COMPOSE_BASE} --env-file certguard-auth-service/.env --env-file "${ENV_DEPLOY}" pull auth-service
log "Image pull complete."

# ── 9. Remove existing containers so compose can replace them ────────────────
log "Removing existing app/gateway/auth-service containers..."
docker rm -f certguard-app certguard-gateway certguard-auth-service-auth-service-1 2>/dev/null || true

# ── 10. Bring services up ────────────────────────────────────────────────────
log "Deploying services (app, gateway, auth-service; no-deps=${NO_DEPS:-false})..."
${COMPOSE_BASE} --env-file .env --env-file "${ENV_DEPLOY}" up -d --no-build --force-recreate ${NO_DEPS} app gateway
${AUTH_COMPOSE_BASE} --env-file certguard-auth-service/.env --env-file "${ENV_DEPLOY}" up -d --no-build --force-recreate --no-deps auth-service
log "docker compose up complete."

# ── 11. Health checks ────────────────────────────────────────────────────────
wait_healthy "certguard-app"                          "${HEALTH_APP_TIMEOUT}"
wait_healthy "certguard-gateway"                      "${HEALTH_GATEWAY_TIMEOUT}"
wait_healthy "certguard-auth-service-auth-service-1"  "${HEALTH_AUTH_TIMEOUT}"

# ── 12. Restart nginx ─────────────────────────────────────────────────────────
# The app/gateway containers were just recreated with new IPs. nginx now uses
# Docker's DNS resolver + variable proxy_pass to re-resolve dynamically, but we
# still restart it here to (a) pick up any nginx.conf changes pulled in step 5 and
# (b) guarantee a clean re-resolution. nginx has no healthcheck, so verify via a
# request through it instead of wait_healthy.
log "Restarting nginx to pick up new upstream IPs / config..."
docker restart certguard-nginx >/dev/null
sleep 3
if docker exec certguard-nginx wget -q --spider --no-check-certificate https://localhost/ 2>/dev/null; then
  log "nginx restarted and serving."
else
  log "WARNING: nginx restarted but a probe through it failed — check: docker logs certguard-nginx"
fi

# ── 13. Cleanup ──────────────────────────────────────────────────────────────
log "Cleaning up ${ENV_DEPLOY}..."
rm -f "${ENV_DEPLOY}"

log "Pruning pg_dump backups older than 7 days..."
find backups/ -name "pgdump-*.sql.gz" -mtime +7 -delete

# ── Done ──────────────────────────────────────────────────────────────────────
log "Deploy complete: ${IMAGE_TAG}"
