// ─── API CLIENT ──────────────────────────────────────────────────────────────
// Centralized HTTP client for CertGuard.
//
// API_BASE  — empty string in production (same-origin); override with
//             VITE_API_BASE env var for local dev pointing at a remote server.
// DEV_MODE  — when true the LaunchScreen shows the dev-login form instead of
//             Google OAuth. Driven by VITE_DEV_MODE env var OR the server's
//             /api/v1/auth/config response (whichever the screen uses).
//
// Session-expiry flow:
//   App calls api.setSessionExpiredHandler(fn) after login. When any
//   authenticated request returns 401, the handler fires exactly once so the
//   app can redirect to the login screen without polling loops.

export const API_BASE = import.meta.env.VITE_API_BASE ?? "";
export const DEV_MODE = import.meta.env.VITE_DEV_MODE === "true";

let sessionExpiredHandler = null;
let sessionExpiredFired = false;

export const api = {
  setSessionExpiredHandler(fn) { sessionExpiredHandler = fn; },
  resetSessionExpired() { sessionExpiredFired = false; },

  async call(method, path, body, token, { actingAsOrgId, reason } = {}) {
    const headers = { "Content-Type": "application/json" };
    if (token) headers["Authorization"] = `Bearer ${token}`;
    if (actingAsOrgId) {
      headers["X-Acting-As-Org"] = actingAsOrgId;
      if (reason) headers["X-Acting-As-Reason"] = reason;
    }
    const res = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      // 402 subscription-suspended — surface a user-friendly message
      if (res.status === 402 && (err.type || "").includes("subscription-suspended")) {
        const suspendedError = new Error("Scans are blocked — subscription is suspended. Contact support to reactivate.");
        suspendedError.status = 402;
        suspendedError.problemDetail = err;
        throw suspendedError;
      }
      // 401 on an authenticated request → the token is expired or revoked. Fire the
      // global session-expiry handler once so the app redirects to login rather than
      // looping on failed polls. Token-less (public-endpoint) 401s are left to the caller.
      if (res.status === 401 && token && sessionExpiredHandler && !sessionExpiredFired) {
        sessionExpiredFired = true;
        sessionExpiredHandler(err);
      }
      // ProblemDetail (RFC 9457) uses title + detail; fall back to message for older endpoints
      const msg = err.detail || err.title || err.message || `HTTP ${res.status}: ${res.statusText}`;
      const error = new Error(msg);
      error.status = res.status;
      error.problemDetail = err; // preserve full object for callers that want it
      throw error;
    }
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  },

  getDevToken: (email, resetOnboarding = false) =>
    api.call("POST", `/api/v1/auth/dev-token?email=${encodeURIComponent(email)}&resetOnboarding=${resetOnboarding}`),
  logout: (token) => api.call("POST", "/api/v1/auth/logout", null, token),
  // Server-authoritative session check: pass token in body only — NOT as the 4th-arg bearer.
  // This prevents api.call's reactive 401 handler from also firing; we handle the result explicitly.
  validateSession: (token) => api.call("POST", "/api/auth/validate", { token }),
  getMe:         (token) => api.call("GET",  "/api/v1/auth/me",            null, token),
  getOrg:        (token) => api.call("GET",  "/api/v1/org",              null, token),
  updateOrgName: (name, token) => api.call("PUT", `/api/v1/org/name?name=${encodeURIComponent(name)}`, null, token),
  completeOnboarding: (data, token) => api.call("POST", "/api/v1/onboarding", data, token),
  // MSP endpoints
  msp: {
    getDashboard: (token) => api.call("GET", "/api/v1/msp/dashboard", null, token),
    getTargets:   (token, page = 0, size = 20, orgId = null) => api.call("GET", `/api/v1/msp/targets?page=${page}&size=${size}${orgId ? `&orgId=${orgId}` : ""}`, null, token),
    listClients:  (token) => api.call("GET", "/api/v1/msp/clients", null, token),
    createClient: (data, token) => api.call("POST", "/api/v1/msp/clients", data, token),
    updateClient: (id, data, token) => api.call("PUT", `/api/v1/msp/clients/${id}`, data, token),
  },
  getTargets:    (token, opts) => api.call("GET",  "/api/v1/targets?size=100", null, token, opts),
  createTarget:  (data, token, opts) => api.call("POST", "/api/v1/targets",    data, token, opts),
  createTargetForOrg: (orgId, data, token) => api.call("POST", `/api/v1/organizations/${orgId}/targets`, data, token),
  updateTarget:  (id, data, token, opts) => api.call("PUT", `/api/v1/targets/${id}`, data, token, opts),
  deleteTarget:  (id, token, opts) => api.call("DELETE", `/api/v1/targets/${id}`, null, token, opts),
  scanTarget:    (id, token, opts) => api.call("POST", `/api/v1/targets/${id}/scan`, null, token, opts),
  getDashboard:  (token) => api.call("GET",  "/api/v1/dashboard",        null, token),
  getCerts:      (token) => api.call("GET",  "/api/v1/certificates?size=100", null, token),
  getExpiring:   (days, token) => api.call("GET", `/api/v1/certificates/expiring?days=${days}`, null, token),
  // Agent endpoints
  listAgents:    (token, opts) => api.call("GET",  "/api/v1/agent/list",                    null,   token, opts),
  genAgentToken: (name, token) => api.call("POST", `/api/v1/agent/tokens?agentName=${encodeURIComponent(name)}`, null, token),
  revokeAgent:   (id, token, opts) => api.call("POST", `/api/v1/agent/${id}/revoke`,        null,   token, opts),
  createAgent:   (data, token, opts) => api.call("POST", "/api/v1/agents",                  data,   token, opts),
  queueScan:     (targetId, token) => api.call("POST", `/api/v1/targets/${targetId}/scan`, null, token),
  // Location endpoints
  listLocations:   (token, opts) => api.call("GET",  "/api/v1/locations",        null, token, opts),
  createLocation:  (data, token, opts) => api.call("POST", "/api/v1/locations",  data, token, opts),
  updateLocation:  (id, data, token, opts) => api.call("PUT", `/api/v1/locations/${id}`, data, token, opts),
  deleteLocation:  (id, token, opts) => api.call("DELETE", `/api/v1/locations/${id}`, null, token, opts),
  // Team endpoints
  listMembers:   (token, opts) => api.call("GET",  "/api/v1/org/members",                    null, token, opts),
  inviteMember:  (data, token, opts) => api.call("POST", "/api/v1/org/invitations",           data, token, opts),
  changeRole:    (userId, role, token, opts) => api.call("PUT", `/api/v1/org/members/${userId}/role?role=${role}`, null, token, opts),
  revokeMember:  (userId, token, opts) => api.call("DELETE", `/api/v1/org/members/${userId}`, null, token, opts),
  // Org profile endpoints
  getOrgProfile:    (token) => api.call("GET", "/api/v1/org/profile",       null, token),
  updateOrgProfile: (data, token) => api.call("PUT", "/api/v1/org/profile", data, token),
  // Invite acceptance endpoints
  validateInvite: (token) => api.call("POST", `/api/v1/auth/invite/validate?token=${encodeURIComponent(token)}`),
  acceptInvite:   (data) => api.call("POST", "/api/v1/auth/invite/accept", data),
  // Email auth — registration & password reset
  registerEmail:        (data) => api.call("POST", "/api/auth/register", data),
  verifyEmail:          (token) => api.call("GET", `/api/auth/verify-email?token=${encodeURIComponent(token)}`),
  resendVerification:   (email) => api.call("POST", "/api/auth/resend-verification", { email }),
  forgotPassword:       (email) => api.call("POST", "/api/auth/forgot-password", { email }),
  resetPassword:        (data)  => api.call("POST", "/api/auth/reset-password", data),
  // Platform admin endpoints
  admin: {
    listOrgs:    (token) => api.call("GET", "/api/v1/admin/orgs", null, token),
    getOrgTree:  (token) => api.call("GET", "/api/v1/admin/orgs/tree", null, token),
    getMsps:     (token) => api.call("GET", "/api/v1/admin/msps", null, token),
    getOrgDetail:(token, orgId) => api.call("GET", `/api/v1/admin/orgs/${orgId}`, null, token),
    updateQuota: (token, orgId, body) => api.call("PUT", `/api/v1/admin/orgs/${orgId}/quota`, body, token),
    getAuditLog: (token, params) => api.call("GET", `/api/v1/admin/audit?${new URLSearchParams(params)}`, null, token),
    promoteMsp:  (token, orgId) => api.call("PATCH", `/api/v1/admin/orgs/${orgId}/promote-msp`, null, token),
  },
  // Certificate renewal endpoints
  getCert:          (certId, token) => api.call("GET",  `/api/v1/certificates/${certId}`, null, token),
  requestRenewal:       (certId, body, token) => api.call("POST", `/api/v1/certificates/${certId}/renewals`, body, token),
  listRenewals:         (certId, token) => api.call("GET",  `/api/v1/certificates/${certId}/renewals`, null, token),
  getRenewal:           (renewalId, token) => api.call("GET",  `/api/v1/renewals/${renewalId}`, null, token),
  cancelRenewal:        (renewalId, token) => api.call("POST", `/api/v1/renewals/${renewalId}/cancel`, null, token),
  renewalPackageUrl:    (renewalId) => `${API_BASE}/api/v1/renewals/${renewalId}/package`,
  listRenewalProviders: (token) => api.call("GET", `/api/v1/renewal/providers`, null, token),
  // Notification settings endpoints (RFC 0008 §3.4)
  getOrgNotificationSettings:    (token) => api.call("GET",  "/api/v1/org/notification-settings", null, token),
  putOrgNotificationSettings:    (body, token) => api.call("PUT", "/api/v1/org/notification-settings", body, token),
  getTargetNotificationSettings: (targetId, token) => api.call("GET",  `/api/v1/targets/${targetId}/notification-settings`, null, token),
  putTargetNotificationSettings: (targetId, body, token) => api.call("PUT",  `/api/v1/targets/${targetId}/notification-settings`, body, token),
  deleteTargetNotificationSettings: (targetId, token) => api.call("DELETE", `/api/v1/targets/${targetId}/notification-settings`, null, token),
  // RFC 0009 — per-cert revocation deep-check toggle (BE-12, FE-3)
  patchCertRevocationDeepCheck: (orgId, certId, enabled, token) =>
    api.call("PATCH", `/api/v1/organizations/${orgId}/certificates/${certId}/revocation-deep-check`, { enabled }, token),

  // RFC 0011/0012 — Network scan endpoints (authenticated)
  networkScans: {
    // body: { agentId, portProfile, customPorts?, cidr? }
    // RFC 0012: cidr is optional — omit it to let the server fan-out across all
    // discovered subnets.  Callers should not include the key when cidr is null/undefined.
    create: (token, orgId, body) =>
      api.call("POST", `/api/v1/organizations/${orgId}/network-scans`, body, token),

    list: (token, orgId, page = 0, size = 20) =>
      api.call("GET", `/api/v1/organizations/${orgId}/network-scans?page=${page}&size=${size}`, null, token),

    get: (token, orgId, scanId) =>
      api.call("GET", `/api/v1/organizations/${orgId}/network-scans/${scanId}`, null, token),

    listEndpoints: (token, orgId, scanId, { state, deviceClass, page = 0, size = 50 } = {}) => {
      const params = new URLSearchParams({ page, size });
      if (state)       params.set("state", state);
      if (deviceClass) params.set("deviceClass", deviceClass);
      return api.call("GET",
        `/api/v1/organizations/${orgId}/network-scans/${scanId}/endpoints?${params}`,
        null, token);
    },

    cancel: (token, orgId, scanId) =>
      api.call("DELETE", `/api/v1/organizations/${orgId}/network-scans/${scanId}`, null, token),
  },

  // RFC 0011 — Anonymous scan endpoints (no auth required)
  anon: {
    createSession: () =>
      api.call("POST", "/api/v1/anon/sessions"),  // unauthenticated; server rate-limits by IP

    getSession: (viewToken) =>
      api.call("GET", `/api/v1/anon/sessions/${viewToken}`),  // no token arg — public endpoint

    claimSession: (viewToken, token) =>
      api.call("POST", `/api/v1/anon/sessions/${viewToken}/claim`, null, token),

    deleteSession: (viewToken) =>
      api.call("DELETE", `/api/v1/anon/sessions/${viewToken}`),
  },
};
