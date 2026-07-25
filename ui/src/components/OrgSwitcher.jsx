import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "./Spinner.jsx";

// ─── ORG SWITCHER ────────────────────────────────────────────────────────────
// RFC 0015 Phase 2 — explicit org switch. Renders a dropdown of the user's
// memberships (org name + role) with the active org selected, and calls the
// switch-org endpoint when the user picks a different one.
//
// Only rendered by Sidebar when the user has more than one membership and is
// not a platform admin — a single-org user has nothing to switch to, and
// platform admins switch org context via act-as-org instead (the backend
// 403s switch-org for them).
//
// Props:
//   me         — /me payload: { memberships: [{orgId, orgName, role}], currentOrg: {id, name, role}, ... }
//   token      — current bearer token, forwarded to api.switchOrg
//   onSwitched — called with the new token on a successful switch; caller is
//                responsible for persisting it and re-running the app's
//                token-refresh flow (App.jsx's handleToken)
//   onRefreshMe — called to refetch /me (e.g. after a 403 removes a stale
//                 membership from the list without a full page reload)
//   toast      — shared toast function (msg, type) — same one threaded through
//                every other panel in this app
export function OrgSwitcher({ me, token, onSwitched, onRefreshMe, toast }) {
  const [switching, setSwitching] = useState(false);
  const memberships = me?.memberships || [];
  const currentOrgId = me?.currentOrg?.id || "";

  const handleChange = async (e) => {
    const orgId = e.target.value;
    if (!orgId || orgId === currentOrgId || switching) return;

    setSwitching(true);
    try {
      const data = await api.switchOrg(token, orgId);
      if (data?.token) {
        onSwitched(data.token);
      } else {
        toast("Switch failed — no token in response", "error");
      }
    } catch (err) {
      // Surface the server's own ProblemDetail message (err.message already
      // resolves to detail/title via api.call) rather than assuming *why* —
      // 403 covers a few distinct cases (revoked/non-member, revoked session,
      // and platform admins who must use act-as-org instead of switch-org).
      if (err.status === 403) {
        toast(err.message, "error");
        // Refetch /me so a genuinely stale/revoked membership drops out of
        // the list without a full reload; harmless no-op for the other 403 cases.
        onRefreshMe?.();
      } else {
        toast("Failed to switch organization: " + err.message, "error");
      }
    } finally {
      setSwitching(false);
    }
  };

  return (
    <div className="org-switcher">
      <select
        className="org-switcher-select"
        value={currentOrgId}
        onChange={handleChange}
        disabled={switching}
        aria-label="Switch organization"
      >
        {memberships.map((m) => (
          <option key={m.orgId} value={m.orgId}>
            {m.orgName} — {m.role}{m.orgId === currentOrgId ? " (current)" : ""}
          </option>
        ))}
      </select>
      {switching && (
        <span className="org-switcher-spinner">
          <Spinner />
        </span>
      )}
    </div>
  );
}
