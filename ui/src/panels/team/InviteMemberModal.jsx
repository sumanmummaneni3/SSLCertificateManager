import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function InviteMemberModal({ token, onClose, onInvited }) {
  const [email, setEmail]   = useState("");
  const [role, setRole]     = useState("ENGINEER");
  const [loading, setLoading] = useState(false);
  const [error, setError]   = useState("");

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!email.trim()) { setError("Email is required"); return; }
    setError("");
    setLoading(true);
    try {
      await api.inviteMember({ email: email.trim(), role }, token);
      onInvited();
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="invite-title">
        <div className="modal-title" id="invite-title">Invite Team Member</div>
        <form onSubmit={handleSubmit}>
          <div className="field">
            <label>Email address</label>
            <input type="email" value={email} autoFocus
              onChange={(e) => setEmail(e.target.value)} placeholder="colleague@example.com" />
          </div>
          <div className="field">
            <label>Role</label>
            <select value={role} onChange={(e) => setRole(e.target.value)}>
              <option value="ADMIN">Admin — full access, can manage team</option>
              <option value="ENGINEER">Engineer — manage targets and scans</option>
              <option value="VIEWER">Viewer — read-only access</option>
            </select>
          </div>
          {error && <div style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }}>{error}</div>}
          <div className="modal-actions">
            <button type="button" className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? <Spinner /> : "Send Invite"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
