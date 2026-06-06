import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function LaunchScreen({ onToken, onPostRegister, onForgotPassword, returnToCertId }) {
  const [email, setEmail]               = useState("");
  const [password, setPassword]         = useState("");
  const [loading, setLoading]           = useState(false);
  const [error, setError]               = useState("");
  const [typed, setTyped]               = useState("");
  const [resetOnboarding, setResetOnboarding] = useState(false);
  // "signin" | "register"
  const [authTab, setAuthTab]           = useState("signin");
  // Registration extra fields
  const [confirmPassword, setConfirmPassword] = useState("");
  // Whether the last login attempt returned EMAIL_NOT_VERIFIED
  const [emailNotVerified, setEmailNotVerified] = useState(false);
  // Runtime dev-mode flag — fetched from /api/v1/auth/config so the login form
  // always matches the server's actual mode regardless of how the bundle was built.
  const [devMode, setDevMode] = useState(null); // null = loading
  const [providers, setProviders]       = useState([]);
  const tagline = "TLS certificate monitoring for teams.";

  useEffect(() => {
    Promise.all([
      fetch("/api/v1/auth/config").then(r => r.json()).catch(() => ({ devMode: false })),
      fetch("/api/auth/providers").then(r => r.json()).catch(() => ({ providers: [] })),
    ]).then(([configRes, providersRes]) => {
      setDevMode(!!configRes.devMode);
      setProviders(providersRes.providers || []);
    });
  }, []);

  // Typewriter effect
  useEffect(() => {
    let i = 0;
    const t = setInterval(() => {
      setTyped(tagline.slice(0, ++i));
      if (i >= tagline.length) clearInterval(t);
    }, 35);
    return () => clearInterval(t);
  }, []);

  const handleDevLogin = async () => {
    setError(""); setLoading(true);
    try {
      const data = await api.getDevToken(email, resetOnboarding);
      if (data?.token) onToken(data.token, data.orgId, data.email);
      else setError("No token in response");
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const handleOAuthLogin = async (providerId) => {
    setError(""); setLoading(true);
    try {
      const callbackUri = window.location.origin + "/api/auth/callback/" + providerId;
      const res = await fetch("/api/auth/initiate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ provider: providerId, redirectUri: callbackUri }),
      });
      if (!res.ok) throw new Error("Failed to initiate login");
      const data = await res.json();
      window.location.href = data.auth_url;
    } catch (e) {
      setError(e.message);
      setLoading(false);
    }
  };

  const handleEmailLogin = async () => {
    setError(""); setEmailNotVerified(false); setLoading(true);
    try {
      const res = await fetch("/api/auth/token", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ provider: "email", email, password }),
      });
      const data = await res.json();
      if (!res.ok) {
        // Check for EMAIL_NOT_VERIFIED in any error field
        const raw = JSON.stringify(data);
        if (res.status === 401 && raw.includes("EMAIL_NOT_VERIFIED")) {
          setEmailNotVerified(true);
          return;
        }
        throw new Error(data.detail || data.message || "Login failed");
      }
      if (data?.token) onToken(data.token, data.orgId, data.email);
      else throw new Error("No token in response");
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const handleEmailRegister = async () => {
    setError(""); setLoading(true);
    if (password !== confirmPassword) {
      setError("Passwords do not match");
      setLoading(false);
      return;
    }
    if (password.length < 8) {
      setError("Password must be at least 8 characters");
      setLoading(false);
      return;
    }
    try {
      await api.registerEmail({ email, password });
      onPostRegister(email);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="launch">
      <div className="launch-logo">
        <div className="logo-icon">🔐</div>
        <div className="logo-text">OOPSSSL</div>
      </div>

      <div style={{ textAlign: "center", marginBottom: "2.5rem" }}>
        <p style={{ color: "var(--muted)", fontSize: "0.9rem", fontFamily: "var(--font-mono)", minHeight: "1.4em" }}>
          {typed}<span className="cursor">|</span>
        </p>
      </div>

      <div className="launch-card">
        <div className="launch-title">{authTab === "register" ? "Create account" : "Welcome back"}</div>
        <p className="launch-sub">
          {authTab === "register"
            ? "Start monitoring your TLS certificates."
            : "Sign in to access your certificate dashboard."}
        </p>
        {returnToCertId && (
          <div className="alert alert-info" role="status" style={{ marginBottom: "1rem" }}>
            Sign in to view the certificate details you were linked to.
          </div>
        )}

        {devMode === null ? (
          <div style={{ textAlign: "center", padding: "1rem" }}><Spinner /></div>
        ) : devMode ? (
          <>
            <div className="dev-badge">⚡ DEV MODE — Google OAuth bypassed</div>
            {error && <div className="alert alert-error">⚠ {error}</div>}
            <div className="field">
              <label htmlFor="dev-email">Dev Login Email</label>
              <input
                id="dev-email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleDevLogin()}
                placeholder="your@email.com"
              />
            </div>
            <label style={{ display: "flex", alignItems: "center", gap: "0.5rem", fontSize: "0.85rem", color: "var(--muted)", cursor: "pointer", marginTop: "0.5rem" }}>
              <input
                type="checkbox"
                checked={resetOnboarding}
                onChange={e => setResetOnboarding(e.target.checked)}
              />
              Reset onboarding (re-trigger setup flow)
            </label>
            <button className="btn btn-primary" onClick={handleDevLogin} disabled={loading}>
              {loading ? <><Spinner /> Authenticating...</> : "→ Get Dev Token"}
            </button>
          </>
        ) : (
          <>
            {/* Sign in / Create account tab switcher — only shown when email provider is enabled */}
            {providers.some(p => p.id === "email") && (
              <div style={{ display: "flex", marginBottom: "1.25rem", borderBottom: "1px solid var(--border)" }}>
                <button
                  className={`admin-tab${authTab === "signin" ? " active" : ""}`}
                  onClick={() => { setAuthTab("signin"); setError(""); setEmailNotVerified(false); }}
                  aria-pressed={authTab === "signin"}
                  style={{ flex: 1 }}
                >
                  Sign in
                </button>
                <button
                  className={`admin-tab${authTab === "register" ? " active" : ""}`}
                  onClick={() => { setAuthTab("register"); setError(""); setEmailNotVerified(false); }}
                  aria-pressed={authTab === "register"}
                  style={{ flex: 1 }}
                >
                  Create account
                </button>
              </div>
            )}

            {error && <div className="alert alert-error" role="alert">⚠ {error}</div>}

            {/* EMAIL_NOT_VERIFIED inline message */}
            {emailNotVerified && (
              <div className="alert alert-warning" role="alert">
                Please verify your email before signing in.{" "}
                <button
                  className="btn-ghost"
                  style={{ display: "inline", padding: "0", fontSize: "inherit", color: "var(--accent)" }}
                  onClick={async () => {
                    try {
                      await api.resendVerification(email);
                    } catch {
                      /* ignore — always says 202 */
                    }
                    onPostRegister(email);
                  }}
                >
                  Resend verification email
                </button>
              </div>
            )}

            {/* Sign In form */}
            {authTab === "signin" && !emailNotVerified && (
              <>
                {providers.filter(p => p.type === "oauth2").map(p => (
                  <button
                    key={p.id}
                    className="btn btn-primary"
                    onClick={() => handleOAuthLogin(p.id)}
                    disabled={loading}
                    style={{ marginBottom: "0.75rem" }}
                  >
                    {loading ? <><Spinner /> Connecting...</> : (
                      <><span aria-hidden="true">{p.id === "google" ? "G" : "W"}</span> Continue with {p.id.charAt(0).toUpperCase() + p.id.slice(1)}</>
                    )}
                  </button>
                ))}

                {providers.some(p => p.id === "email") && (
                  <>
                    {providers.some(p => p.type === "oauth2") && (
                      <div style={{ display: "flex", alignItems: "center", gap: "0.75rem", margin: "1rem 0", color: "var(--muted)", fontSize: "0.8rem" }} role="separator" aria-label="or">
                        <div style={{ flex: 1, height: "1px", background: "var(--border)" }} />
                        or
                        <div style={{ flex: 1, height: "1px", background: "var(--border)" }} />
                      </div>
                    )}
                    <div className="field">
                      <label htmlFor="email-input">Email</label>
                      <input
                        id="email-input"
                        type="email"
                        autoComplete="username"
                        value={email}
                        onChange={e => setEmail(e.target.value)}
                        placeholder="you@example.com"
                      />
                    </div>
                    <div className="field">
                      <label htmlFor="password-input">Password</label>
                      <input
                        id="password-input"
                        type="password"
                        autoComplete="current-password"
                        value={password}
                        onChange={e => setPassword(e.target.value)}
                        onKeyDown={e => e.key === "Enter" && handleEmailLogin()}
                        placeholder="••••••••"
                      />
                    </div>
                    <div style={{ textAlign: "right", marginBottom: "1rem", marginTop: "-0.5rem" }}>
                      <button
                        className="btn-ghost"
                        style={{ fontSize: "0.75rem", padding: "0", color: "var(--muted)" }}
                        onClick={() => onForgotPassword()}
                      >
                        Forgot password?
                      </button>
                    </div>
                    <button className="btn btn-secondary" onClick={handleEmailLogin} disabled={loading}>
                      {loading ? <><Spinner /> Signing in...</> : "Sign in with Email"}
                    </button>
                  </>
                )}

                {providers.length === 0 && (
                  <p style={{ color: "var(--muted)", textAlign: "center", fontSize: "0.85rem" }}>
                    Loading sign-in options...
                  </p>
                )}
              </>
            )}

            {/* Create Account form */}
            {authTab === "register" && (
              <>
                <div className="field">
                  <label htmlFor="reg-email-input">Email</label>
                  <input
                    id="reg-email-input"
                    type="email"
                    autoComplete="username"
                    value={email}
                    onChange={e => setEmail(e.target.value)}
                    placeholder="you@example.com"
                  />
                </div>
                <div className="field">
                  <label htmlFor="reg-password-input">Password</label>
                  <input
                    id="reg-password-input"
                    type="password"
                    autoComplete="new-password"
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    placeholder="Min. 8 characters"
                  />
                </div>
                <div className="field">
                  <label htmlFor="reg-confirm-input">Confirm password</label>
                  <input
                    id="reg-confirm-input"
                    type="password"
                    autoComplete="new-password"
                    value={confirmPassword}
                    onChange={e => setConfirmPassword(e.target.value)}
                    onKeyDown={e => e.key === "Enter" && handleEmailRegister()}
                    placeholder="Re-enter password"
                  />
                </div>
                <button className="btn btn-primary" onClick={handleEmailRegister} disabled={loading || !email || !password}>
                  {loading ? <><Spinner /> Creating account...</> : "Create account"}
                </button>
              </>
            )}
          </>
        )}
      </div>

      <p className="text-muted text-sm" style={{ marginTop: "2rem" }}>
        API — <span className="text-accent">{window.location.origin}</span>
      </p>
    </div>
  );
}
