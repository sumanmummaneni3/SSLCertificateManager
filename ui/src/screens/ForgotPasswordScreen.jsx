import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function ForgotPasswordScreen({ onBack }) {
  const [email, setEmail]     = useState("");
  const [loading, setLoading] = useState(false);
  const [sent, setSent]       = useState(false);
  const [error, setError]     = useState("");

  const handleSubmit = async (ev) => {
    ev.preventDefault();
    if (!email.trim()) return;
    setLoading(true); setError("");
    try {
      await api.forgotPassword(email.trim());
      setSent(true);
    } catch {
      // The endpoint always returns 202; any thrown error is a network issue
      setSent(true); // still show the generic message to avoid leaking account existence
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
        <div className="launch-title">Reset your password</div>

        {sent ? (
          <>
            <p className="launch-sub">
              If an account exists for that email, we sent a password reset link. Check your inbox.
            </p>
            <button className="btn btn-secondary" onClick={onBack}>Back to sign in</button>
          </>
        ) : (
          <form onSubmit={handleSubmit}>
            <p className="launch-sub">Enter your account email and we will send you a reset link.</p>
            {error && <div className="alert alert-error" role="alert">{error}</div>}
            <div className="field">
              <label htmlFor="forgot-email">Email</label>
              <input
                id="forgot-email"
                type="email"
                autoComplete="username"
                value={email}
                onChange={e => setEmail(e.target.value)}
                placeholder="you@example.com"
                autoFocus
              />
            </div>
            <button type="submit" className="btn btn-primary" disabled={loading || !email.trim()} style={{ marginBottom: "0.75rem" }}>
              {loading ? <><Spinner /> Sending...</> : "Send reset link"}
            </button>
            <button type="button" className="btn btn-ghost" onClick={onBack} style={{ width: "100%", textAlign: "center" }}>
              Back to sign in
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
