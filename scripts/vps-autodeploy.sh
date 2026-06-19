#!/usr/bin/env bash
# vps-autodeploy.sh — Pull-based deploy trigger for a private-network VPS.
#
# Why this exists: the VPS has no inbound SSH, so GitHub Actions cannot push a
# deploy to it. Instead the VPS reaches OUT to GHCR on a schedule. This script
# polls the published :latest image digests for app/gateway/auth-service and,
# when any of them differs from what was last deployed, triggers a full deploy
# via vps-deploy.sh.
#
# We deliberately do NOT let a container-updater (e.g. Watchtower) swap the
# containers directly, because a real deploy here is multi-step host
# orchestration that vps-deploy.sh owns: pg_dump backup, keystore backup,
# `git pull` of compose files, force-recreate, health gating, and an nginx
# restart. Watchtower's container-swap model can't do those, so this script is
# the trigger and vps-deploy.sh is the executor.
#
# Run on a schedule by certguard-autodeploy.timer (see scripts/systemd/).
#
# Usage (normally invoked by the timer, but can be run manually):
#   bash /opt/certguard/scripts/vps-autodeploy.sh
#
# Environment variables (read from .env at DEPLOY_PATH):
#   GITHUB_REPOSITORY_OWNER — owner of the GHCR packages
#   GITHUB_PAT              — PAT with read:packages, to pull private images
#
# State file ($DEPLOY_PATH/.autodeploy-state) records the digests last deployed.
# On the very first run it is seeded WITHOUT deploying (the box is assumed to be
# running latest at install time). To force a deploy, delete the state file.

set -euo pipefail

# ── Configuration ────────────────────────────────────────────────────────────
DEPLOY_PATH="${DEPLOY_PATH:-/opt/certguard}"
STATE_FILE="${DEPLOY_PATH}/.autodeploy-state"
LOCK_FILE="/tmp/certguard-autodeploy.lock"
IMAGE_TAG="${IMAGE_TAG:-latest}"
# ─────────────────────────────────────────────────────────────────────────────

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

cd "${DEPLOY_PATH}"

# ── Single-instance guard ─────────────────────────────────────────────────────
# Prevents a slow deploy from overlapping with the next timer tick.
exec 9>"${LOCK_FILE}"
if ! flock -n 9; then
  log "Another autodeploy run is in progress; exiting."
  exit 0
fi

# ── Load credentials ─────────────────────────────────────────────────────────
if [ ! -f .env ]; then
  log "ERROR: .env not found at ${DEPLOY_PATH}/.env."
  exit 1
fi
set -o allexport
# shellcheck disable=SC1091
source .env
set +o allexport

OWNER="${GITHUB_REPOSITORY_OWNER:?GITHUB_REPOSITORY_OWNER must be set in .env}"
: "${GITHUB_PAT:?GITHUB_PAT must be set in .env}"

IMAGES=(
  "ghcr.io/${OWNER}/certguard-app:${IMAGE_TAG}"
  "ghcr.io/${OWNER}/certguard-gateway:${IMAGE_TAG}"
  "ghcr.io/${OWNER}/certguard-auth-service:${IMAGE_TAG}"
)

# ── Authenticate to GHCR (images are private) ────────────────────────────────
echo "${GITHUB_PAT}" | docker login ghcr.io -u "${OWNER}" --password-stdin >/dev/null
log "Authenticated to GHCR as ${OWNER}."

# ── Resolve the currently published digest of each image ─────────────────────
# `docker pull` is cheap when nothing changed (layers are already local); after
# it, RepoDigests[0] is the registry digest that :latest currently points to.
current_state=""
for img in "${IMAGES[@]}"; do
  log "Checking ${img} ..."
  docker pull -q "${img}" >/dev/null
  digest="$(docker inspect --format '{{index .RepoDigests 0}}' "${img}")"
  current_state+="${digest}"$'\n'
done

# ── First run: seed the baseline, do not deploy ──────────────────────────────
if [ ! -f "${STATE_FILE}" ]; then
  printf '%s' "${current_state}" > "${STATE_FILE}"
  log "Baseline digests recorded in ${STATE_FILE}; no deploy on first run."
  log "(Delete ${STATE_FILE} to force a deploy on the next tick.)"
  exit 0
fi

# ── No change: nothing to do ─────────────────────────────────────────────────
previous_state="$(cat "${STATE_FILE}")"
if [ "${current_state}" = "${previous_state}" ]; then
  log "No image changes detected; nothing to deploy."
  exit 0
fi

# ── Change detected: trigger the real deploy ─────────────────────────────────
log "New image digest(s) detected — triggering vps-deploy.sh ${IMAGE_TAG} --no-deps."
if bash "${DEPLOY_PATH}/scripts/vps-deploy.sh" "${IMAGE_TAG}" --no-deps; then
  printf '%s' "${current_state}" > "${STATE_FILE}"
  log "Deploy succeeded; state updated."
else
  rc=$?
  log "ERROR: vps-deploy.sh failed (exit ${rc}). State left unchanged so it retries next tick."
  exit "${rc}"
fi
