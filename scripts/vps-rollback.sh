#!/usr/bin/env bash
# vps-rollback.sh — Roll back the running stack to a previous image tag.
#
# NOTE: After cloning the repo to the VPS for the first time, make this script executable:
#   chmod +x /opt/certguard/scripts/vps-rollback.sh
#
# Usage:
#   bash /opt/certguard/scripts/vps-rollback.sh <PREVIOUS_TAG>
#
# PREVIOUS_TAG: The Docker image tag to revert to (SHA or version string).
#               List available local tags with:
#                 docker images ghcr.io/<OWNER>/certguard-app --format "{{.Tag}}"
#               Or look up the previous tag in GitHub Actions deploy run history.
#
# IMPORTANT — Database caveat:
#   Flyway migrations are forward-only. Rolling back the app image does NOT undo
#   any schema migrations that ran during the failed deploy. See:
#   docs/architecture/runbooks/rollback.md for full guidance on when a DB restore
#   is also required.

set -euo pipefail

# ── Configuration ────────────────────────────────────────────────────────────
PREVIOUS_TAG="${1:-}"
DEPLOY_PATH="${DEPLOY_PATH:-/opt/certguard}"
COMPOSE_BASE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"
ENV_DEPLOY=".env.deploy"
HEALTH_APP_TIMEOUT=120
# ─────────────────────────────────────────────────────────────────────────────

if [ -z "${PREVIOUS_TAG}" ]; then
  echo "ERROR: PREVIOUS_TAG argument is required."
  echo "Usage: bash vps-rollback.sh <previous-sha-or-tag>"
  echo ""
  echo "To list locally available tags:"
  echo "  docker images ghcr.io/\$GITHUB_REPOSITORY_OWNER/certguard-app --format '{{.Tag}}'"
  exit 1
fi

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

# ── 2. Source .env for credentials ───────────────────────────────────────────
if [ ! -f .env ]; then
  log "ERROR: .env file not found at ${DEPLOY_PATH}/.env. Cannot proceed."
  exit 1
fi
set -o allexport
# shellcheck disable=SC1091
source .env
set +o allexport

log "Rolling back to ${PREVIOUS_TAG}..."

# ── 3. Write previous tag to .env.deploy ─────────────────────────────────────
printf 'APP_IMAGE_TAG=%s\n' "${PREVIOUS_TAG}" > "${ENV_DEPLOY}"
log "Rollback tag written to ${ENV_DEPLOY}."

# ── 4. Pull the previous images ───────────────────────────────────────────────
log "Pulling images for tag ${PREVIOUS_TAG}..."
${COMPOSE_BASE} --env-file .env --env-file "${ENV_DEPLOY}" pull app gateway ui
log "Image pull complete."

# ── 5. Bring services up with the previous images ─────────────────────────────
log "Starting services with previous tag..."
${COMPOSE_BASE} --env-file .env --env-file "${ENV_DEPLOY}" up -d --no-build
log "docker compose up complete."

# ── 6. Health check ───────────────────────────────────────────────────────────
wait_healthy "certguard-app" "${HEALTH_APP_TIMEOUT}"

# ── 7. Cleanup ────────────────────────────────────────────────────────────────
log "Cleaning up ${ENV_DEPLOY}..."
rm -f "${ENV_DEPLOY}"

log "Rollback complete. Running tag: ${PREVIOUS_TAG}"
log ""
log "Next steps:"
log "  1. File a GitHub issue describing what failed."
log "  2. Pin APP_IMAGE_TAG in .env to ${PREVIOUS_TAG} until the issue is fixed."
log "  3. Consult docs/architecture/runbooks/rollback.md if a DB restore is needed."
