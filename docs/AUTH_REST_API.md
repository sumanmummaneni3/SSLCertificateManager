# CertGuard Auth Service — REST API for External (PHP) Integration

## Summary

**Yes — all three required operations are supported by plain REST/JSON calls**, so a PHP
application can integrate without any SDK or shared secret:

| Requirement | Supported | Endpoint |
|-------------|-----------|----------|
| 1. Allow the user to login | ✅ | `POST /api/auth/token` |
| 2. Check if a user is logged in | ✅ | `POST /api/auth/validate` |
| 3. Register a new user | ✅ | `POST /api/auth/register` |

All three endpoints are **public** (no auth required to call them — see `SecurityConfig`)
and accept/return `application/json`.

> ⚠️ **One caveat on registration:** registration does **not** return a login token.
> It sends a verification email and returns `202 Accepted`. The user must verify their
> email (`GET /api/auth/verify-email?token=…`) **before** they can log in via
> `/api/auth/token`. See [Registration](#3-register-a-new-user) below.

---

## Where the Auth API lives

Authentication is handled by a **standalone microservice**, not in-process in the main
CertGuard server:

- **Service:** `certguard-auth-service` (Spring Boot)
- **Controller:** `com.certguard.auth.controller.AuthController`
  (`certguard-auth-service/src/main/java/com/certguard/auth/controller/AuthController.java`)
- **Base path:** `/api/auth`
- **Default port:** `8090` (env `AUTH_SERVER_PORT`)
- **Tokens:** RS256-signed JWTs, issuer `certguard-cloud`. Public key for offline
  verification is published at `GET /api/auth/.well-known/jwks.json`.

The PHP app can call the auth service directly (`http://<auth-host>:8090/api/auth/...`)
or through the API gateway (`:8080`) if one is deployed in front of it.

### CORS / cross-origin notes

If the PHP app calls these endpoints **from the browser**, the calling origin must be in
the auth service's allowed-origins list:

- Env var: `AUTH_CORS_ALLOWED_ORIGINS` (default `http://localhost:3000,http://localhost:5173`)
- Allowed headers: `Authorization`, `Content-Type`, `X-Forwarded-For`
- Credentials allowed: yes

If the PHP **backend** calls server-to-server (recommended), CORS does not apply.

### Rate limiting

`/api/auth/token`, `/api/auth/register` (and other initiate/resend/forgot endpoints) are
rate-limited per client IP. Exceeding the limit returns **`429 Too Many Requests`** with a
`Retry-After` header. The service reads the real client IP from `X-Forwarded-For` (first
value) — set this header when proxying through PHP so per-user limits aren't applied to the
PHP host's IP.

---

## 1. Login

Exchange email + password for a JWT.

```
POST /api/auth/token
Content-Type: application/json
```

**Request body** (`TokenRequest`):

| Field      | Type   | Required        | Notes |
|------------|--------|-----------------|-------|
| `provider` | string | **yes**         | Must be `email` for password login. (`google` / `microsoft` are for OAuth flows.) |
| `email`    | string | yes (email)     | User's email address |
| `password` | string | yes (email)     | User's password |

```json
{
  "provider": "email",
  "email": "user@example.com",
  "password": "MyPassword123"
}
```

**Success — `200 OK`** (`TokenResponse`):

```json
{
  "token": "<RS256 JWT>",
  "token_type": "Bearer",
  "expires_in": 86400,
  "user_id": "0f2c…-uuid",
  "provider": "email",
  "email": "user@example.com",
  "name": "User Name"
}
```

- Store `token` and send it as `Authorization: Bearer <token>` on subsequent calls.
- `expires_in` is seconds (24h by default; effectively non-expiring for platform admins).

**Errors:**
- `401 Unauthorized` — bad credentials, or email not yet verified (RFC 9457 `ProblemDetail`).
- `400 Bad Request` — validation failure (missing/blank fields).
- `429 Too Many Requests` — rate limited.

**PHP example (cURL):**

```php
$ch = curl_init('http://auth-host:8090/api/auth/token');
curl_setopt_array($ch, [
    CURLOPT_POST           => true,
    CURLOPT_HTTPHEADER     => ['Content-Type: application/json'],
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_POSTFIELDS     => json_encode([
        'provider' => 'email',
        'email'    => $email,
        'password' => $password,
    ]),
]);
$body   = curl_exec($ch);
$status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

if ($status === 200) {
    $data  = json_decode($body, true);
    $token = $data['token'];          // store in session
} else {
    // 401 = bad credentials / unverified email; 429 = rate limited
}
```

---

## 2. Check if a user is logged in

Validate a token. This both checks the JWT signature **and** confirms the session still
exists server-side (so revoked/logged-out tokens are correctly rejected — a plain local
JWT check would miss revocation).

```
POST /api/auth/validate
Content-Type: application/json
```

**Request body** (`ValidateRequest`):

| Field   | Type   | Required | Notes |
|---------|--------|----------|-------|
| `token` | string | **yes**  | The JWT obtained from `/api/auth/token` |

```json
{ "token": "<JWT>" }
```

**Success — `200 OK`** (`ValidateResponse`) means the user **is logged in**:

```json
{
  "valid": true,
  "user_id": "0f2c…-uuid",
  "provider": "email",
  "email": "user@example.com",
  "name": "User Name",
  "provider_ids": { "email": "user@example.com" },
  "exp": 1760000000,
  "iat": 1759913600,
  "iss": "certguard-cloud"
}
```

**Not logged in — `401 Unauthorized`:** returned when the token is invalid, expired, or
the session was revoked (logout). Treat any non-200 as "not logged in."

> Tip: to check login status, the PHP app calls `/api/auth/validate` with the stored token
> and treats `200` → logged in, `401` → not logged in (clear the stored token).

**PHP example:**

```php
function isLoggedIn(string $token): bool {
    $ch = curl_init('http://auth-host:8090/api/auth/validate');
    curl_setopt_array($ch, [
        CURLOPT_POST           => true,
        CURLOPT_HTTPHEADER     => ['Content-Type: application/json'],
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POSTFIELDS     => json_encode(['token' => $token]),
    ]);
    curl_exec($ch);
    $status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return $status === 200;
}
```

**Alternative — `GET /api/users/me`** returns the current user's profile but requires the
`Authorization: Bearer <token>` header and returns `401` if not authenticated. Use
`/api/auth/validate` when you specifically need to confirm login state.

---

## 3. Register a new user

Email/password sign-up. **Returns `202 Accepted` and sends a verification email — it does
NOT return a login token.** The user cannot log in until the email is verified.

```
POST /api/auth/register
Content-Type: application/json
```

**Request body** (`EmailRegisterRequest`):

| Field      | Type   | Required | Constraints |
|------------|--------|----------|-------------|
| `email`    | string | **yes**  | Valid email format (`@Email`), non-blank |
| `password` | string | **yes**  | 8–128 characters (`@Size(min=8, max=128)`) |
| `name`     | string | no       | Display name; defaults to the email prefix if omitted |

```json
{
  "email": "newuser@example.com",
  "password": "MyPassword123",
  "name": "New User"
}
```

**Success — `202 Accepted`:**

```json
{ "message": "Verification email sent to newuser@example.com. Please check your inbox." }
```

**Errors:**
- `409 Conflict` — email already registered.
- `400 Bad Request` — validation failure (invalid email, password too short/long).
- `429 Too Many Requests` — rate limited.

**Post-registration flow (required before login works):**

1. User receives an email containing a verification link.
2. The link hits `GET /api/auth/verify-email?token=<token>` (token valid 24h).
   - Returns `200 OK` `{ "message": "Email verified successfully. You can now sign in." }`
3. Only then will `POST /api/auth/token` succeed for that user.
4. To re-send: `POST /api/auth/resend-verification` with `{ "email": "..." }` (always
   returns `202` to avoid email enumeration).

**PHP example:**

```php
$ch = curl_init('http://auth-host:8090/api/auth/register');
curl_setopt_array($ch, [
    CURLOPT_POST           => true,
    CURLOPT_HTTPHEADER     => ['Content-Type: application/json'],
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_POSTFIELDS     => json_encode([
        'email'    => $email,
        'password' => $password,
        'name'     => $name,      // optional
    ]),
]);
$body   = curl_exec($ch);
$status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

// 202 = success (verification email sent); 409 = email already exists; 400 = invalid input
```

---

## Logout (bonus)

To invalidate a session (so `/api/auth/validate` starts returning `401`):

```
DELETE /api/auth/session        # current session
DELETE /api/auth/sessions       # all sessions for the user (all devices)
Authorization: Bearer <token>
```

Both return `204 No Content`.

---

## Error format

All error responses use RFC 9457 `ProblemDetail` JSON (`application/problem+json`):

```json
{
  "type": "about:blank",
  "title": "Authentication failed",
  "status": 401,
  "detail": "Invalid credentials"
}
```

Status mapping: `AuthException` → `401`, `ConflictException` → `409`,
`TooManyRequestsException` → `429`, bean-validation failure → `400`,
unexpected error → `500`.

---

## Quick reference

| Operation | Method | Path | Auth | Success |
|-----------|--------|------|------|---------|
| Login | POST | `/api/auth/token` | public | `200` + JWT |
| Check logged in | POST | `/api/auth/validate` | public | `200` (valid) / `401` (not) |
| Register | POST | `/api/auth/register` | public | `202` (verification email) |
| Verify email | GET | `/api/auth/verify-email?token=…` | public | `200` |
| Current user | GET | `/api/users/me` | Bearer | `200` profile |
| Logout | DELETE | `/api/auth/session` | Bearer | `204` |
| JWKS (public key) | GET | `/api/auth/.well-known/jwks.json` | public | JWK set |

*Source: `certguard-auth-service` — `AuthController.java`, `SecurityConfig.java`, request/response DTOs under `dto/request` and `dto/response`.*
