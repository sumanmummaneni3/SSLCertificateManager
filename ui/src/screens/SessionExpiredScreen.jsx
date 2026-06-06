// Shown whenever expireSession() is called (reactive 401, idle timeout, or nav guard).
// Gives the user a clear explanation before sending them back to the login screen.
export function SessionExpiredScreen({ message, onSignIn }) {
  return (
    <div className="launch">
      <div className="launch-logo">
        <div className="logo-icon" aria-hidden="true">🔐</div>
        <div className="logo-text">OOPSSSL</div>
      </div>
      <div className="launch-card">
        <div className="launch-title" style={{ color: "var(--red)" }}>Session expired</div>
        <p className="launch-sub">
          {message || "Your session is no longer valid. Please sign in again to continue."}
        </p>
        <button className="btn btn-primary" onClick={onSignIn}>
          Sign in again
        </button>
      </div>
    </div>
  );
}
