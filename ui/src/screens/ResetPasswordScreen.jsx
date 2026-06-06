import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function ResetPasswordScreen({ resetToken, onGoToSignIn }) {
  const [newPassword, setNewPassword]       = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [loading, setLoading]               = useState(false);
  const [status, setStatus]                 = useState("form"); // form | success | error
  const [error, setError]                   = useState("");

  const handleSubmit = async (ev) => {
    ev.preventDefault();
    if (newPassword !== confirmPassword) { setError("Passwords do not match"); return; }
    if (newPassword.length < 8) { setError("Password must be at least 8 characters"); return; }
    setLoading(true); setError("");
    try {
      await api.resetPassword({ token: resetToken, newPassword });
      setStatus("success");
    } catch (e) {
      setError(e.message || "Password reset failed. The link may have expired.");
      setStatus("error");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="launch">
      <div className="launch-logo">
        <div className="logo-icon" aria-hidden="true">🔐</div>
        <div className="logo-text">OOPSSSL</div>
      </div>

      <div className="launch-card">
        {status === "success" ? (
          <>
            <div className="launch-title" style={{ color: "var(--green)" }}>Password reset</div>
            <p className="launch-sub">Your password has been updated. You can now sign in with your new password.</p>
            <button className="btn btn-primary" onClick={onGoToSignIn}>Sign in</button>
          </>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className="launch-title">Set a new password</div>
            <p className="launch-sub">Enter and confirm your new password below.</p>

            {error && <div className="alert alert-error" role="alert">{error}</div>}

            <div className="field">
              <label htmlFor="reset-password-input">New password</label>
              <input
                id="reset-password-input"
                type="password"
                autoComplete="new-password"
                value={newPassword}
                onChange={e => setNewPassword(e.target.value)}
                placeholder="Min. 8 characters"
                autoFocus
              />
            </div>
            <div className="field">
              <label htmlFor="reset-confirm-input">Confirm new password</label>
              <input
                id="reset-confirm-input"
                type="password"
                autoComplete="new-password"
                value={confirmPassword}
                onChange={e => setConfirmPassword(e.target.value)}
                onKeyDown={e => e.key === "Enter" && handleSubmit(e)}
                placeholder="Re-enter new password"
              />
            </div>
            <button type="submit" className="btn btn-primary" disabled={loading || !newPassword || !confirmPassword} style={{ marginBottom: "0.75rem" }}>
              {loading ? <><Spinner /> Resetting...</> : "Reset password"}
            </button>
            <button type="button" className="btn btn-ghost" onClick={onGoToSignIn} style={{ width: "100%", textAlign: "center" }}>
              Back to sign in
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
