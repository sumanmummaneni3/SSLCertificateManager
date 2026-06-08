import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { fmtDate } from "@/lib/helpers.js";
import { Spinner, Badge } from "@/components/index.js";
import { RoleDropdown } from "./RoleDropdown.jsx";
import { InviteMemberModal } from "./InviteMemberModal.jsx";

export function TeamView({ token, org, toast, me }) {
  const [members, setMembers]       = useState([]);
  const [loading, setLoading]       = useState(true);
  const [showInvite, setShowInvite] = useState(false);
  const [revokeId, setRevokeId]     = useState(null);
  const [revoking, setRevoking]     = useState(false);

  const load = () => {
    setLoading(true);
    api.listMembers(token)
      .then(setMembers)
      .catch((e) => toast("Failed to load members: " + e.message, "error"))
      .finally(() => setLoading(false));
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps -- load is a stable inline fn; toast is stable
  useEffect(load, [token]);

  const handleRevoke = async () => {
    setRevoking(true);
    try {
      await api.revokeMember(revokeId, token);
      toast("Member removed", "success");
      setRevokeId(null);
      load();
    } catch (e) {
      toast("Failed to remove member: " + e.message, "error");
    } finally {
      setRevoking(false);
    }
  };

  const inviteStatusBadgeType = (s) => ({ ACCEPTED: "active", PENDING: "pending", REVOKED: "revoked" }[s] || "unknown");

  const canManage = me == null || me?.permissions?.canManageTeam;
  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Team</div>
          <div className="page-sub">Manage members and access for {org?.name || "your organisation"}</div>
        </div>
        {canManage && (
          <button className="btn btn-primary btn-sm" onClick={() => setShowInvite(true)}>+ Invite Member</button>
        )}
      </div>
      <div className="page-content">
        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading members...</span></div>
        ) : members.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">◎</div>
            <div className="empty-title">No team members yet</div>
            <p className="empty-sub">Invite your colleagues to collaborate on certificate monitoring.</p>
            {canManage && (
              <button className="btn btn-primary btn-sm" style={{ margin: "0 auto" }} onClick={() => setShowInvite(true)}>+ Invite Member</button>
            )}
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Member</th>
                  <th>Role</th>
                  <th>Status</th>
                  <th>Invited By</th>
                  <th>Joined</th>
                  {canManage && <th></th>}
                </tr>
              </thead>
              <tbody>
                {members.map((m) => (
                  <tr key={m.id}>
                    <td>
                      <div style={{ fontWeight: 500 }}>{m.name || m.email}</div>
                      {m.name && <div className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>{m.email}</div>}
                    </td>
                    <td>
                      {canManage
                        ? <RoleDropdown member={m} token={token} onChanged={load} toast={toast} />
                        : <Badge type={m.role === "ADMIN" ? "active" : "pending"}>{m.role}</Badge>
                      }
                    </td>
                    <td><Badge type={inviteStatusBadgeType(m.inviteStatus)}>{m.inviteStatus}</Badge></td>
                    <td className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>{m.invitedByEmail || "—"}</td>
                    <td className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>{fmtDate(m.createdAt)}</td>
                    {canManage && (
                      <td>
                        <button className="scan-btn" style={{ color: "var(--red)", borderColor: "rgba(255,82,82,0.3)" }}
                          onClick={() => setRevokeId(m.userId)} title="Remove member">✕</button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showInvite && (
        <InviteMemberModal token={token} onClose={() => setShowInvite(false)}
          onInvited={() => { setShowInvite(false); load(); toast("Invitation sent", "success"); }}
          toast={toast} />
      )}

      {revokeId && (
        <div className="modal-bg">
          <div className="modal" role="alertdialog" aria-modal="true">
            <div className="modal-title">Remove Member?</div>
            <p className="modal-sub">This member will lose access to your organisation immediately.</p>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setRevokeId(null)} disabled={revoking}>Cancel</button>
              <button className="btn btn-danger" onClick={handleRevoke} disabled={revoking}>
                {revoking ? <Spinner /> : "Remove"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
