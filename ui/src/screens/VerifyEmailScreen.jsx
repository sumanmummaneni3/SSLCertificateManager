import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

// The verification token from the email link is consumed here. If invalid/expired,
// the user is sent back to sign-in where EMAIL_NOT_VERIFIED will offer a resend option.
export function VerifyEmailScreen({ verifyToken, onGoToSignIn }) {
  // Derive initial state synchronously — no token means error right away
  const [status, setStatus]   = useState(() => verifyToken ? "loading" : "error");
  const [errorMsg, setErrorMsg] = useState(() => verifyToken ? "" : "No verification token found in the link.");

  useEffect(() => {
    if (!verifyToken) return; // already set to error in initial state
    let cancelled = false;
    api.verifyEmail(verifyToken)
      .then(() => { if (!cancelled) setStatus("success"); })
      .catch((e) => {
        if (!cancelled) {
          setErrorMsg(e.message || "Verification failed.");
          setStatus("error");
        }
      });
    return () => { cancelled = true; };
  }, [verifyToken]);

  return (
    <div className="launch">
      <div className="launch-logo">
        <div className="logo-icon" aria-hidden="true">✉</div>
        <div className="logo-text">OOPSSSL</div>
      </div>

      <div className="launch-card">
        {status === "loading" && (
          <div className="loading-center" style={{ padding: "2rem 0" }}>
            <Spinner lg />
            <span>Verifying your email...</span>
          </div>
        )}

        {status === "success" && (
          <>
            <div className="launch-title" style={{ color: "var(--green)" }}>Email verified</div>
            <p className="launch-sub">Your email address has been confirmed. You can now sign in.</p>
            <button className="btn btn-primary" onClick={onGoToSignIn}>Sign in</button>
          </>
        )}

        {status === "error" && (
          <>
            <div className="launch-title" style={{ color: "var(--red)" }}>Link invalid or expired</div>
            <p className="launch-sub">{errorMsg || "This verification link is invalid or has expired."}</p>
            <p className="launch-sub" style={{ marginTop: "-1rem" }}>
              Try signing in — you can request a new verification email from the sign-in page.
            </p>
            <button className="btn btn-secondary" onClick={onGoToSignIn} style={{ marginBottom: "0.75rem" }}>
              Resend verification email
            </button>
            <button className="btn btn-ghost" onClick={onGoToSignIn} style={{ width: "100%", textAlign: "center" }}>
              Back to sign in
            </button>
          </>
        )}
      </div>
    </div>
  );
}
