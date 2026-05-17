# CertGuard Cloud — REST API Reference

**Base URL:** `https://cloud.oopsssl.co.uk`

All requests route through the nginx reverse proxy → API gateway → downstream service. The gateway validates the JWT on every protected endpoint and injects trusted `X-CG-*` headers for internal services.

---

## Authentication

Protected endpoints require a Bearer JWT in the `Authorization` header:

```
Authorization: Bearer <token>
```

Tokens are issued by the auth-service after a successful OAuth2 or email login. They expire after 8 hours by default (`AUTH_JWT_EXPIRATION_SECONDS`).

### Roles

| Role | Description |
|------|-------------|
| `VIEWER` | Read-only access to org data |
| `ENGINEER` | Read + write targets, locations, agents |
| `ADMIN` | Full org management including team and billing |
| `PLATFORM_ADMIN` | Cross-org superuser; can act as any org |

---

## Auth Service (`/api/auth/**`)

These endpoints are served by the **certguard-auth-service** and are proxied by the gateway without JWT validation.

### Providers

#### `GET /api/auth/providers`
Returns enabled authentication providers. Public — no token required.

**Response `200`**
```json
{
  "providers": [
    { "id": "google",    "type": "oauth2" },
    { "id": "microsoft", "type": "oauth2" },
    { "id": "email",     "type": "password" }
  ]
}
```

---

### OAuth2 Login Flow

#### `POST /api/auth/initiate`
Returns the authorization URL to redirect the user to for OAuth2 login.

**Request**
```json
{ "provider": "google", "redirectUri": "https://cloud.oopsssl.co.uk/api/auth/callback/google" }
```

| Field | Values |
|-------|--------|
| `provider` | `google` \| `microsoft` \| `email` |
| `redirectUri` | Must match a URI registered in the OAuth app console |

**Response `200`**
```json
{ "provider": "google", "authUrl": "https://accounts.google.com/o/oauth2/auth?...", "message": null }
```

---

#### `GET /api/auth/callback/google?code={code}`
Google redirects the browser here after user consent. Exchanges the authorization code, creates a session, and redirects the browser to:
```
{AUTH_CALLBACK_BASE_URL}/auth/callback#token={jwt}&expiresIn={seconds}&email={email}
```
The UI reads the token from the URL fragment on the `/auth/callback` route.

**Response** `302 Found` → UI callback URL with token in fragment

---

#### `GET /api/auth/callback/microsoft?code={code}`
Same flow as the Google callback but for Microsoft identity.

**Response** `302 Found` → UI callback URL with token in fragment

---

### Email Login

#### `POST /api/auth/token`
Exchange provider credentials for a JWT. Used directly for email/password login.

**Request**
```json
{ "provider": "email", "email": "alice@example.com", "password": "secret" }
```

For Microsoft code exchange:
```json
{ "provider": "microsoft", "code": "...", "redirectUri": "https://..." }
```

**Response `200`**
```json
{
  "token":     "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 28800,
  "userId":    "uuid",
  "provider":  "email",
  "email":     "alice@example.com",
  "name":      "Alice"
}
```

---

#### `POST /api/auth/register`
Register a new account with email and password. Returns a session token immediately.

**Request**
```json
{ "email": "alice@example.com", "password": "secret", "name": "Alice" }
```

**Response `201`** — same shape as `/api/auth/token`

---

### Token Validation

#### `POST /api/auth/validate`
Validate a JWT and return its decoded claims. Useful for server-side consumers that cannot share the signing key.

**Request**
```json
{ "token": "eyJ..." }
```

**Response `200`**
```json
{
  "userId":   "uuid",
  "email":    "alice@example.com",
  "orgId":    "uuid",
  "orgRole":  "ADMIN",
  "provider": "google",
  "expiresAt": "2026-05-17T18:00:00Z"
}
```

---

### Session Management

#### `DELETE /api/auth/session`
Revoke the current session. The token is immediately invalid.

**Header:** `Authorization: Bearer <token>`

**Response `204 No Content`**

---

#### `DELETE /api/auth/sessions`
Revoke all active sessions for the authenticated user (logout from all devices).

**Header:** `Authorization: Bearer <token>`

**Response `204 No Content`**

---

### User Profile (Auth Service)

#### `GET /api/users/me`
Returns the authenticated user's profile from the auth-service user store.

**Response `200`**
```json
{
  "id":            "uuid",
  "email":         "alice@example.com",
  "name":          "Alice",
  "provider":      "google",
  "emailVerified": true,
  "createdAt":     "2026-05-17T10:00:00Z"
}
```

---

### JWKS (Public Key)

#### `GET /api/auth/.well-known/jwks.json`
Returns the RS256 public key used to sign JWTs. Used by the gateway and any downstream service that validates tokens independently.

**Response `200`** — standard JWKS JSON

---

### Dev Token *(dev-mode only)*

#### `POST /api/auth/dev-token?email=&provider=&name=`
Issues a real JWT without going through Google/Microsoft. Only active when `AUTH_DEV_MODE=true`. **Never enable in production.**

---

---

## App Server (`/api/v1/**`)

These endpoints are served by the **certguard-server** and require a valid Bearer token unless noted as public.

### Session / Identity

#### `GET /api/v1/auth/config`
Returns server-side feature flags. Public — no token required.

**Response `200`**
```json
{ "devMode": false }
```

---

#### `POST /api/v1/auth/logout`
Server-side logout acknowledgement. Public.

**Response `200`** `{ "message": "Logged out" }`

---

#### `GET /api/v1/auth/me`
Returns the authenticated user's identity, current org, all org memberships, and computed permission flags.

**Response `200`**
```json
{
  "user": {
    "id": "uuid",
    "email": "alice@example.com",
    "onboardingCompleted": true,
    "onboardingCompletedAt": "2026-05-17T10:00:00Z"
  },
  "platformAdmin": false,
  "currentOrg": {
    "id": "uuid",
    "name": "Acme Ltd",
    "orgType": "SINGLE",
    "role": "ADMIN"
  },
  "memberships": [
    { "orgId": "uuid", "orgName": "Acme Ltd", "role": "ADMIN" }
  ],
  "permissions": {
    "canManageTeam": true,
    "canWriteTargets": true,
    "canWriteAgents": true,
    "canWriteLocations": true,
    "canManageMspClients": true,
    "canEditOrgProfile": true,
    "canViewAllOrgs": false,
    "canActAsOrg": false,
    "isMspMember": false,
    "canAccessBilling": true
  }
}
```

---

### Invitations

#### `POST /api/v1/auth/invite/validate?token={token}`
Step 1 of invite acceptance. Validates the invite token and sends an OTP to the invited email. Public.

**Response `200`** `{ "email": "alice@example.com", "message": "OTP sent to alice@example.com" }`

---

#### `POST /api/v1/auth/invite/accept`
Step 2 of invite acceptance. Submits the OTP to complete onboarding. Public.

**Request**
```json
{ "token": "invite-token", "email": "alice@example.com", "otp": "123456" }
```

**Response `200`** `{ "message": "...", "jwtToken": "eyJ..." }`

---

### Onboarding

#### `POST /api/v1/onboarding`
Completes the onboarding flow for the authenticated user's org (sets org name, etc.).

**Request**
```json
{ "orgName": "Acme Ltd" }
```

**Response `200`** — `OrgResponse` (see Org section)

---

### Organisation

#### `GET /api/v1/org`
Get the current org summary.

**Response `200`** — `OrgResponse`

---

#### `GET /api/v1/org/profile`
Get the full org profile including contact address, phone, and email.

**Response `200`** — `OrgResponse`

---

#### `PUT /api/v1/org/profile` *(ADMIN)*
Update the org profile.

**Request** — `UpdateOrgProfileRequest` (name, address, phone, etc.)

**Response `200`** — `OrgResponse`

---

#### `POST /api/v1/org/request-msp-upgrade` *(ADMIN)*
Submit a request for MSP tier upgrade.

**Request**
```json
{ "reason": "We resell to 20+ clients" }
```

**Response `202 Accepted`**

---

#### `POST /api/v1/org/request-quota-increase` *(ADMIN)*
Submit a request for a higher certificate quota.

**Request**
```json
{ "requestedQuota": 500, "reason": "Expanding infrastructure" }
```

**Response `202 Accepted`**

---

### Team

#### `GET /api/v1/org/members`
List all members of the current org.

**Response `200`** — array of `OrgMemberResponse`

---

#### `POST /api/v1/org/invitations` *(ADMIN)*
Invite a user to join the org by email.

**Request**
```json
{ "email": "bob@example.com", "role": "ENGINEER" }
```

| `role` | Values |
|--------|--------|
| | `VIEWER` \| `ENGINEER` \| `ADMIN` |

**Response `201`** — `InvitationResponse`

---

#### `PUT /api/v1/org/members/{userId}/role` *(ADMIN)*
Change a member's role.

**Query param:** `role=ENGINEER`

**Response `200`** — `OrgMemberResponse`

---

#### `DELETE /api/v1/org/members/{userId}` *(ADMIN)*
Remove a member from the org.

**Response `204 No Content`**

---

### Targets

A *target* is a TLS endpoint (hostname + port) to monitor.

#### `GET /api/v1/targets`
List targets for the current org. Supports Spring `Pageable` query parameters (`page`, `size`, `sort`).

**Response `200`** — paginated `TargetResponse`

---

#### `POST /api/v1/targets` *(ADMIN / ENGINEER)*
Create a new target.

**Request**
```json
{
  "hostname": "api.example.com",
  "port": 443,
  "displayName": "Production API",
  "locationId": "uuid-or-null"
}
```

**Response `201`** — `TargetResponse`

---

#### `PUT /api/v1/targets/{id}` *(ADMIN / ENGINEER)*
Update a target.

**Request** — `UpdateTargetRequest` (same fields as create)

**Response `200`** — `TargetResponse`

---

#### `DELETE /api/v1/targets/{id}` *(ADMIN / ENGINEER)*
Delete a target.

**Response `204 No Content`**

---

#### `POST /api/v1/targets/{id}/scan` *(ADMIN / ENGINEER)*
Trigger an immediate TLS scan for the target.

**Response `200`** `{ "message": "Scan triggered" }`

---

#### `GET /api/v1/targets/{id}/scan-status`
Get the latest scan status and result for a target.

**Response `200`** — scan status object with `status`, `lastScannedAt`, `expiresAt`, etc.

---

#### `GET /api/v1/targets/{id}/notifications`
Get the notification channel configuration for a target.

**Response `200`** — notification channels map

---

#### `PUT /api/v1/targets/{id}/notifications` *(ADMIN / ENGINEER)*
Update notification channels for a target (email, Slack, webhook, etc.).

**Request**
```json
{
  "email": { "enabled": true, "addresses": ["ops@example.com"] },
  "slack": { "enabled": false }
}
```

**Response `200`** — `TargetResponse`

---

### Certificates

#### `GET /api/v1/certificates`
List all certificates seen for the current org (paginated).

**Response `200`** — paginated `CertificateResponse`

---

#### `GET /api/v1/certificates/expiring?days={n}`
List certificates expiring within `n` days (default: 30).

**Response `200`** — array of `CertificateResponse`

---

#### `GET /api/v1/dashboard`
Get the dashboard summary: total targets, expiry buckets, health breakdown.

**Response `200`** — `DashboardResponse`

---

### Locations

A *location* represents a network segment where a private agent is deployed.

#### `GET /api/v1/locations`
List all locations for the current org.

**Response `200`** — array of `LocationResponse`

---

#### `GET /api/v1/locations/{id}`
Get a single location.

**Response `200`** — `LocationResponse`

---

#### `POST /api/v1/locations` *(ADMIN / ENGINEER)*
Create a location.

**Request**
```json
{ "name": "HQ Private Network", "description": "On-prem data centre" }
```

**Response `201`** — `LocationResponse`

---

#### `PUT /api/v1/locations/{id}` *(ADMIN / ENGINEER)*
Update a location.

**Response `200`** — `LocationResponse`

---

#### `DELETE /api/v1/locations/{id}` *(ADMIN / ENGINEER)*
Delete a location.

**Response `204 No Content`**

---

### Agents

Agents are self-hosted processes that scan private-network TLS endpoints on behalf of the cloud server.

#### `POST /api/v1/agents` *(ADMIN / ENGINEER)*
Create a new agent and issue a one-time installer bundle (ZIP containing JAR + pre-filled config).

**Request**
```json
{ "name": "DC1 Agent", "locationId": "uuid-optional" }
```

**Response `201`**
```json
{
  "agentId":           "uuid",
  "installKey":        "one-time-key",
  "bundleDownloadUrl": "/api/v1/agents/{id}/bundle?dlToken=...",
  "expiresAt":         "2026-05-17T11:00:00Z"
}
```

---

#### `GET /api/v1/agents/{agentId}/bundle?dlToken={token}`
One-time bundle download. No JWT required — the `dlToken` is the credential. Returns `410 Gone` if already consumed or expired.

**Response `200`** — `application/zip` binary

---

#### `POST /api/v1/agent/tokens` *(ADMIN / ENGINEER)*
Generate a standalone registration token (alternative to the bundle flow).

**Query param:** `agentName=My+Agent`

**Response `201`** — `RegistrationTokenResponse` with `token`, `expiresAt`

---

#### `GET /api/v1/agent/config` *(ADMIN / ENGINEER)*
Download a pre-filled `application.properties` for the agent.

**Query params:** `agentName`, `token`

**Response `200`** — `text/plain` file download

---

#### `GET /api/v1/agent/list`
List all registered agents for the current org.

**Response `200`** — array of `AgentResponse`

---

#### `POST /api/v1/agent/{agentId}/revoke` *(ADMIN / ENGINEER)*
Revoke an agent's credentials. The agent will stop being able to communicate with the server.

**Response `204 No Content`**

---

#### Agent Self-Registration and Communication *(agent use only)*

These endpoints are called by the agent process, not by the UI.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/v1/agent/register` | `X-Org-Id` header + reg token in body | First-time agent enrollment |
| `POST` | `/api/v1/agent/heartbeat` | `X-Agent-Key` | Periodic liveness signal |
| `GET`  | `/api/v1/agent/jobs`      | `X-Agent-Key` | Poll for pending scan jobs |
| `POST` | `/api/v1/agent/results`   | `X-Agent-Key` | Submit completed scan result |

---

### Agent Download *(public)*

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/agent/download` | Download the agent JAR |
| `GET` | `/agent/version`  | Current agent version string |

---

### MSP (Managed Service Provider)

Available to orgs with `orgType = MSP`.

#### `GET /api/v1/msp/dashboard`
Aggregated dashboard across all client orgs.

**Response `200`** — `MspDashboardResponse`

---

#### `GET /api/v1/msp/targets`
All targets across all client orgs (paginated).

**Response `200`** — paginated `MspTargetRow`

---

#### `GET /api/v1/msp/clients`
List all client orgs managed by this MSP.

**Response `200`** — array of `OrgResponse`

---

#### `GET /api/v1/msp/clients/{clientOrgId}`
Get a specific client org. Caller must be the direct MSP parent.

**Response `200`** — `OrgResponse`

---

#### `POST /api/v1/msp/clients` *(ADMIN)*
Create a new client org under this MSP.

**Request**
```json
{ "name": "Client Co Ltd", "adminEmail": "admin@clientco.com" }
```

**Response `201`** — `OrgResponse`

---

#### `PUT /api/v1/msp/clients/{clientOrgId}` *(ADMIN)*
Update a client org profile.

**Response `200`** — `OrgResponse`

---

### Cross-Org Targets (MSP / Platform Admin)

#### `POST /api/v1/organizations/{orgId}/targets` *(ADMIN / ENGINEER + MSP access)*
Create a target directly under a specific org without switching TenantContext. The caller's home org must be the target org itself or its direct MSP parent.

**Request** — same as `POST /api/v1/targets`

**Response `201`** — `TargetResponse`

---

### Platform Admin

All endpoints under `/api/v1/admin/` require `PLATFORM_ADMIN` role.

#### `GET /api/v1/admin/orgs`
Flat list of all organisations with member count and subscription info.

---

#### `GET /api/v1/admin/orgs/tree`
Org hierarchy: MSP orgs at root with `clients[]` nested; SINGLE orgs at root with empty `clients[]`.

---

#### `GET /api/v1/admin/orgs/{orgId}`
Full org detail including address and contact fields.

---

#### `GET /api/v1/admin/msps`
List all MSP-type orgs.

---

#### `PUT /api/v1/admin/orgs/{orgId}/quota?value={n}`
Update the certificate quota for any org.

---

#### `PATCH /api/v1/admin/orgs/{orgId}/promote-msp?reason={text}`
Promote an org to MSP tier.

---

#### `PATCH /api/v1/admin/orgs/{orgId}/demote-msp`
Demote an MSP org back to SINGLE.

---

#### `DELETE /api/v1/admin/orgs/{orgId}?reason={text}`
Archive (soft-delete) an org.

---

#### `POST /api/v1/admin/orgs/{orgId}/restore`
Restore an archived org.

---

#### `GET /api/v1/admin/audit`
Paginated platform-admin audit log.

**Query params:** `orgId` (optional), `from` (ISO-8601), `to` (ISO-8601), `page`, `size`

**Response `200`** — paginated `PlatformAdminAudit`

---

### Platform Admin — Sales API Keys

#### `POST /api/v1/admin/sales-keys`
Create a sales API key for internal webhook integrations. Requires `PLATFORM_ADMIN`.

#### `GET /api/v1/admin/sales-keys`
List all active sales API keys.

#### `DELETE /api/v1/admin/sales-keys/{id}`
Revoke a sales API key.

---

## Error Responses

All errors use [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) format:

```json
{
  "status":    403,
  "type":      "https://certguard.dev/problems/access-denied",
  "title":     "Forbidden",
  "detail":    "You do not have permission to perform this action",
  "instance":  "/api/v1/targets/uuid",
  "timestamp": "2026-05-17T10:30:00Z"
}
```

| Status | Meaning |
|--------|---------|
| `400` | Validation failure or bad request body |
| `401` | Missing or invalid Bearer token |
| `403` | Authenticated but insufficient role |
| `404` | Resource not found (org-scoped — returns 404 not 403 to avoid enumeration) |
| `409` | Conflict (duplicate resource) |
| `429` | Rate limit exceeded (auth endpoints) |
| `503` | Upstream service unavailable |

---

## Rate Limiting

Auth-service endpoints (`/api/auth/initiate`, `/api/auth/token`, `/api/auth/register`) enforce a sliding-window rate limit per client IP:

- Default: 10 requests per 5-minute window
- Configurable via `AUTH_RATE_LIMIT_MAX_ATTEMPTS` and `AUTH_RATE_LIMIT_WINDOW_SECONDS`
