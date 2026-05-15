# RFC 0003: Split Per-Service Dockerfiles and Move docker-compose.yml to Repo Root

- **Status**: Accepted
- **Date**: 2026-05-15
- **Author**: CertGuard Architect
- **Audience**: Backend-engineer, Frontend-engineer, Ops
- **Related**: `docs/architecture/HLD.md`, `server/Dockerfile`,
  `certguard-gateway/Dockerfile`, `server/docker-compose.yml`

## Context

The current image-build topology conflates concerns inside
`server/Dockerfile`:

- Stage 1 builds the React UI (`server/Dockerfile:4-13`).
- Stage 2 builds the agent JAR (`server/Dockerfile:19-43`).
- Stage 3 builds the server JAR and **copies the UI dist into
  `src/main/resources/static/` and the agent JAR into
  `src/main/resources/agent/` before packaging** (`server/Dockerfile:74-77`).
- Stage 4 is the runtime.

`server/docker-compose.yml:57` consequently sets `context: ..` for the
`app` service so the build can see `ui/`, `agent/`, and `server/`. The
gateway has its own self-contained Dockerfile but the compose file lives
under `server/`, which is misleading: it is the *whole stack*'s compose,
not the server's.

Three problems result:

1. **Cache invalidation cascades.** A one-line CSS change re-runs
   `mvn package` on the server because the UI dist is copied into
   `src/main/resources/static/` before `mvn package`
   (`server/Dockerfile:74, 77`). CI minutes compound.

2. **Build-context bloat.** `context: ..` ships the entire repo (docs,
   the other service tree, etc.) to the Docker daemon every build unless
   `.dockerignore` is meticulous. Slows local builds.

3. **No independent UI deploy.** A UI hotfix requires rebuilding and
   redeploying the server JAR. Per Phase 4 of the production deploy plan,
   that is ~60s of API downtime for a CSS fix.

## Decision

**Split into three first-class Dockerfiles plus an optional fourth for the
agent. Move `docker-compose.yml` to the repo root. Each service is built
from its own subtree.**

| Dockerfile | Builds | Runtime artifact | Build context |
|---|---|---|---|
| `ui/Dockerfile` (NEW) | React/Vite production bundle | `nginx:alpine` serving the SPA on `:80` | `./ui` |
| `server/Dockerfile` (TRIMMED) | Spring Boot server JAR only | `eclipse-temurin:25-jre-alpine` running `app.jar` on `:8443` | `./server` |
| `certguard-gateway/Dockerfile` (UNCHANGED) | Spring WebFlux gateway JAR | JRE 25 running gateway on `:8080` | `./certguard-gateway` |
| `agent/Dockerfile` (NEW, recommended) | Plain Java agent JAR | Published as GHCR generic artifact / OCI artifact; **not run in compose** | `./agent` |

## Per-Dockerfile responsibilities

### `ui/Dockerfile`

- Stage 1: `node:20-alpine`, `npm ci`, `VITE_DEV_MODE=false npm run build`.
- Stage 2: `nginx:alpine` serving `/usr/share/nginx/html` with a small
  bundled `ui/nginx.conf` that:
  - Does SPA fallback: `try_files $uri /index.html;`
  - Proxies `/api`, `/oauth2`, `/login` to `http://gateway:8080`
    (mirroring `ui/vite.config.js:7-22` for production).
- Listens on `:80` inside `certguard-net`. No TLS — the outer `nginx`
  service terminates.

### `server/Dockerfile` (trimmed)

- **Remove** stages 1 (UI build) and 2 (agent build) entirely
  (`server/Dockerfile:1-43`).
- **Remove** the `COPY --from=ui-builder` and `COPY --from=agent-builder`
  lines (`server/Dockerfile:74-75`).
- Keep stages 3 (builder) and 4 (runtime) with their existing Maven 3.9.8
  pin, JDK 25 base, and non-root `certguard` user.
- Build context becomes `./server/` — no longer needs the repo root.

### `certguard-gateway/Dockerfile`

- No changes. Already self-contained, context `./certguard-gateway`.

### `agent/Dockerfile` (new, optional but recommended)

- Stage 1: JDK 25, `mvn package` on `agent/pom.xml`.
- Stage 2: minimal JRE image that can run the agent for CI scenarios, or a
  scratch image with just the JAR for publishing as an OCI artifact.
  Recommendation: publish as a GHCR generic artifact tagged with the server
  release SHA.
- **Not referenced by `docker-compose.yml`** — the agent is customer-side.
  Published by CI as a release asset that customers (and the server's
  agent-download endpoint) can pull.

## Single root `docker-compose.yml`

Move from `server/docker-compose.yml` to
`/home/msuman/git/SSLCertificateManager/docker-compose.yml`.

Required path rewrites (every relative path in the current file shifts):

| Current path (under `server/`) | New path (from repo root) |
|---|---|
| `./postgres/init` | `./server/postgres/init` |
| `./rabbitmq/rabbitmq.conf` | `./server/rabbitmq/rabbitmq.conf` |
| `./certs` | `./server/certs` |
| `./monitoring/prometheus.yml` | `./server/monitoring/prometheus.yml` |
| `./nginx/nginx.conf` | `./server/nginx/nginx.conf` |

Required build-block rewrites:

```yaml
app:
  build:
    context: ./server          # was: ..
    dockerfile: Dockerfile     # was: server/Dockerfile

gateway:
  build:
    context: ./certguard-gateway   # was: ..
    dockerfile: Dockerfile         # was: certguard-gateway/Dockerfile

ui:                              # NEW service
  build:
    context: ./ui
    dockerfile: Dockerfile
  depends_on:
    gateway:
      condition: service_healthy
  healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:80/ | grep -q 'id=\"root\"' || exit 1"]
    interval: 30s
    timeout: 5s
    retries: 3
    start_period: 20s
  networks: [certguard-net]
```

`nginx` service mount paths shift to `./server/nginx/nginx.conf` and
`./server/certs`. Its `nginx.conf` upstream block must be rewritten — see
"Things that will break" below.

## UI hosting decision

Three shapes were considered; the table is recorded for posterity:

| Shape | UI served by | Pros | Cons |
|---|---|---|---|
| **A. UI inside server JAR (today)** | Spring static resources from `src/main/resources/static/` | Single container, single deploy artifact | UI changes force server rebuild + restart; couples release cycles |
| **B. UI as separate nginx container** | New `ui` service `:80` | Independent deploys, fast UI iteration, classic SPA pattern | One more service to operate; outer nginx upstream changes |
| **C. UI served by the outer nginx** | The existing `nginx` service | One fewer container | Outer nginx tied to UI release cycles; messier ownership |

**Decision: B (separate `ui` nginx container).** The repo already has
React 19 + Vite 7 with no SSR, and the production proxy config in
`vite.config.js:7-22` makes B almost a copy-paste. C ties TLS terminator
releases to UI releases — a step backward. A is what exists today and is
what this RFC is moving away from.

## Agent distribution decision

Two options were considered for serving the agent JAR after the split:

- **(a)** Server reads agent JAR from a mounted volume populated by a
  one-shot init container.
- **(b)** Agent JAR is published as a GHCR generic artifact tagged with
  the server release SHA; the server's agent-download endpoint
  redirects/proxies to the artifact URL.

**Decision: (b).** Reasons:

- Decouples agent rotation from server rebuild (today, bumping the agent
  forces a server image rebuild because of `server/Dockerfile:75`).
- Customers can pull the agent directly from the registry — no compose
  volume coupling.
- Versioning is explicit: every agent JAR has a SHA tag.

**Required server-side change**: the agent download endpoint (currently
serving `certguard-agent.jar` from `src/main/resources/agent/`) must be
updated to either redirect to the published URL or proxy-stream it. This is
the load-bearing backend change of this RFC. Owner: backend-engineer.

## Migration order (5 PRs, zero-downtime)

Sequenced so production keeps working at every PR — no window where the
split is half-done.

### PR 1 — Add `ui/Dockerfile` + `ui/nginx.conf`

- Add `ui/Dockerfile` and `ui/nginx.conf`.
- Do **not** touch anything else.
- Acceptance: `docker build ui/` produces a runnable image; manual
  `docker run -p 8081:80 <image>` serves the SPA.
- Production unchanged.

### PR 2 — Add `agent/Dockerfile` and switch agent-download endpoint

- Add `agent/Dockerfile`.
- Add CI job that publishes the agent JAR as a GHCR generic artifact
  tagged with the commit SHA on each release.
- **Backend**: change the agent-download endpoint to redirect to the
  published artifact URL. Configuration via env var:
  `AGENT_ARTIFACT_URL_TEMPLATE=https://ghcr.io/.../certguard-agent:%s`
  (substituted with the running server's release tag).
- Acceptance: an enrolled agent successfully fetches its JAR via the new
  endpoint in staging.
- **Must land before PR 3** so the next PR can safely drop the embedded JAR.

### PR 3 — Trim `server/Dockerfile` to single-purpose

- Remove stages 1 and 2 (`server/Dockerfile:1-43`).
- Remove the `COPY --from=ui-builder` and `COPY --from=agent-builder`
  lines (`server/Dockerfile:74-75`).
- Verify Spring Boot tests still pass (no resource path that relied on the
  embedded UI dist).
- Acceptance: `docker build -f server/Dockerfile server/` succeeds; the
  resulting container starts and serves `/actuator/health`.

### PR 4 — Move `docker-compose.yml` to repo root, wire `ui` service

- Move `server/docker-compose.yml` → `docker-compose.yml`.
- Rewrite every relative path (see table above).
- Rewrite the two `build:` blocks to use the per-subdir contexts.
- Add the `ui` service.
- Update `server/nginx/nginx.conf` to split routing between `ui:80` and
  `gateway:8080` (see "Things that will break" #4).
- Update the production deploy script to reference the new compose path.
- Acceptance: full stack starts on a clean host; UI loads through the outer
  nginx; `/api/...` reaches the gateway; agent enrollment works.

### PR 5 — CI: build and push three images

- Update `.github/workflows/release.yml` to build and push three images
  (`certguard-app`, `certguard-gateway`, `certguard-ui`) instead of two.
- Add the `certguard-agent` artifact-publish job (GHCR generic artifact
  established in PR 2).
- Acceptance: a tagged release produces all four artifacts; the deploy
  workflow pulls them by SHA.

## Things that will break if done naively

1. **Spring Boot resource-includes in `server/pom.xml`.** Verify before
   merging PR 3: search for `<resources>` blocks or
   `spring-boot-maven-plugin` `<includes>` that expect the UI dist at
   `src/main/resources/static`. If absent, safe.

2. **The agent download endpoint.** Code in the server reads from
   `src/main/resources/agent/certguard-agent.jar`. After PR 3 that resource
   no longer exists. **PR 2 must land first** or agent auto-updates break
   silently the moment PR 3 deploys.

3. **`vite.config.js` is dev-only proxy.** The production UI image needs its
   own `ui/nginx.conf` that proxies `/api`, `/oauth2`, `/login` to
   `gateway:8080`. Trivial to write but easy to forget when copy-pasting
   from the dev config (`ui/vite.config.js:7-22`).

4. **`server/nginx/nginx.conf` upstream block.** Currently routes everything
   to `gateway:8080` (`server/nginx/nginx.conf:8-9, 25`). After the split it
   needs two upstreams:
   - `location /` → `http://ui:80`
   - `location /api`, `/oauth2`, `/login`, `/actuator` → `http://gateway:8080`

   **Audit the gateway route table before writing this config** — any path
   the gateway owns that is not in the list above will 404 against the UI
   nginx instead of reaching the gateway. Owner: backend-engineer must
   produce the authoritative path list.

5. **Healthchecks on the new `ui` service.** Nginx will start before the UI
   is "ready" without one. Use:
   `wget -qO- http://localhost:80/ | grep -q 'id="root"' || exit 1`

6. **`.dockerignore` files per subtree.** Each context (`./ui`, `./server`,
   `./certguard-gateway`, `./agent`) needs its own `.dockerignore` to keep
   build contexts lean — `target/`, `node_modules/`, `dist/`, `.idea/`, etc.
   Missing this negates the build-context win.

7. **`server/` context no longer sees the agent source.** After PR 3 the
   server context is `./server/`. Confirm it still contains `pom.xml` and
   `src/`. The `postgres/init/` directory is mounted at runtime via compose,
   not built into the image, so it does not need to be inside the Docker
   context — but it must remain at `server/postgres/init/` because compose
   mounts it from there.

8. **CI checkout scope.** With three independent contexts, build jobs can
   use sparse-checkout for speed. The **deploy** step still needs the full
   repo at the target SHA to read `docker-compose.yml` — keep full checkout
   there.

## Open questions

**All questions decided (2026-05-15). No blockers for PR 2.**

- **Agent artifact URL scheme**: **GHCR generic artifact.** Published as a
  GHCR package alongside the container images for parity with the image
  registry. Template:
  `AGENT_ARTIFACT_URL_TEMPLATE=https://ghcr.io/ORG/certguard-agent:%s`
  (substituted with the running server's release SHA at request time).
- **Versioning policy for the agent JAR**: **lockstep with server release SHA.**
  Every agent JAR is tagged with the same SHA as the server image that released
  it. Independent semver deferred until the agent's protocol surface stabilises.

## Consequences

- **Smaller, faster, parallel image builds.** UI changes no longer trigger
  Java rebuilds; gateway changes no longer touch server.
- **Independent UI deploys** become possible. UI hotfix path: bump `ui`
  image, `docker compose up -d ui`, no app/gateway/nginx restart.
- **Build-context size drops dramatically** for each image.
- **One new service to operate** (`ui` nginx container). Cost: trivial.
- **One new artifact to publish** (`certguard-agent` GHCR generic artifact).
  Cost: one CI job.
- **Migration is bounded** to 5 reviewable PRs over 1-2 sprints. No big bang.

## Owners

- PR 1, PR 4 (UI changes), PR 5 (CI): frontend-engineer
- PR 2 (agent endpoint), PR 3 (server Dockerfile), PR 4 (compose + outer
  nginx): backend-engineer
- RFC sign-off, runbook updates: architect
