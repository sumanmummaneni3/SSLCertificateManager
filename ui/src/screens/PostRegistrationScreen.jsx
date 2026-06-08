import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function PostRegistrationScreen({ email, onBack }) {
  const [resending, setResending] = useState(false);
  const [resent, setResent]       = useState(false);
  const [resentError, setResentError] = useState("");

  const handleResend = async () => {
    setResending(true); setResent(false); setResentError("");
    try {
      await api.resendVerification(email);
      setResent(true);
    } catch {
      // The endpoint always returns 202; if it throws it's a network error
      setResentError("Could not resend. Please try again.");
    } finally {
      setResending(false);
    }
  };

  return (
    <div className="launch">
      <div className="launch-logo">
        <div className="logo-icon" aria-hidden="true">✉</div>
        <div className="logo-text">OOPSSSL</div>
      </div>

      <div className="launch-card">
        <div className="launch-title">Check your inbox</div>
        <p className="launch-sub">
          We sent a verification email to <strong style={{ color: "var(--text)" }}>{email}</strong>.
          Click the link in the email to activate your account.
        </p>

        {resent && (
          <div className="alert alert-info" role="status">
            Verification email resent — check your inbox.
          </div>
        )}
        {resentError && (
          <div className="alert alert-error" role="alert">{resentError}</div>
        )}

        <button className="btn btn-secondary" onClick={handleResend} disabled={resending} style={{ marginBottom: "0.75rem" }}>
          {resending ? <><Spinner /> Sending...</> : "Resend email"}
        </button>

        <button className="btn btn-ghost" onClick={onBack} style={{ width: "100%", textAlign: "center" }}>
          Back to sign in
        </button>
      </div>
    </div>
  );
}
