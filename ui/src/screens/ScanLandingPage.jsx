import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";
import "./ScanLandingPage.css";

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Extract the raw viewToken from whatever the user pastes:
 *   - https://certguard.example.com/scan/abc123  → "abc123"
 *   - /scan/abc123                               → "abc123"
 *   - abc123                                     → "abc123"
 */
function extractViewToken(input) {
  const trimmed = input.trim();
  const scanIdx = trimmed.lastIndexOf("/scan/");
  if (scanIdx !== -1) {
    return trimmed.slice(scanIdx + 6).split("/")[0];
  }
  // Assume raw token — strip any leading slashes
  return trimmed.replace(/^\/+/, "").split("/")[0];
}

// ── Download & Run step ───────────────────────────────────────────────────────

/**
 * DownloadStep — shown after the session is created (step === "download").
 * Renders "Step 2 of 3" instructions: download link + run command + CTA to
 * navigate to the dashboard.
 */
function DownloadStep({ sessionData, onViewResults }) {
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState("");

  const handleDownload = async () => {
    setDownloading(true);
    setDownloadError("");
    try {
      // Build the download URL relative to the current origin so it routes
      // through Vite's proxy in dev and works same-origin on VPS.
      const url = `/api/v1/anon/download?token=${encodeURIComponent(sessionData.scanToken)}`;
      const res = await fetch(url);
      if (!res.ok) throw new Error(`Server returned ${res.status}`);
      const blob = await res.blob();
      const blobUrl = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = blobUrl;
      a.download = "certguard-scanner.zip";
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(blobUrl);
    } catch (e) {
      setDownloadError("Download failed — please try again.");
    } finally {
      setDownloading(false);
    }
  };

  return (
    <section
      className="scan-download-step"
      aria-label="Step 2 of 3 — Download and run the scanner"
    >
      <div className="scan-landing-badge" aria-hidden="true">
        Step 2 of 3
      </div>

      <h2 className="scan-download-heading">
        Download and run the scanner
      </h2>

      <p className="scan-landing-sub" style={{ maxWidth: 520 }}>
        The scanner runs entirely on your machine — your IPs never leave your
        network. When it finishes, your results appear here automatically.
      </p>

      {/* Primary download CTA */}
      <div className="scan-download-cta-wrap">
        <button
          className="btn btn-primary scan-landing-cta-btn"
          onClick={handleDownload}
          disabled={downloading}
          aria-label="Download certguard-scanner.zip"
        >
          {downloading ? "Downloading…" : "Download certguard-scanner.zip"}
        </button>
        {downloadError && (
          <p className="alert-error" style={{ marginTop: 8 }}>{downloadError}</p>
        )}
      </div>

      {/* Run command */}
      <div
        className="scan-download-cmd-block"
        aria-label="Command to run the scanner"
      >
        <p className="scan-download-cmd-label">Then run:</p>
        <code className="scan-download-cmd">java -jar certguard-agent.jar</code>
      </div>

      <p className="scan-download-note">
        The scanner will take 2–5 minutes. When it finishes, your results will
        appear automatically.
      </p>

      {/* Navigate to dashboard */}
      <button
        className="btn scan-download-view-btn"
        onClick={onViewResults}
        aria-label="View my scan results dashboard"
      >
        View my results →
      </button>
    </section>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

/**
 * ScanLandingPage — marketing entry point for the anonymous free-tier scan.
 *
 * State machine:
 *   idle     → hero + CTA button
 *   creating → spinner on the button
 *   download → "Step 2 of 3" Download & Run instructions (new)
 *   error    → inline error banner, back to idle
 *
 * The returning-visitor card is always rendered in all states.
 *
 * Props:
 *   onSessionCreated(viewToken) — called after a session is successfully
 *                                  created or when the user pastes a view link.
 *                                  App.jsx will pushState + switch to anon-scan phase.
 *   onSignIn()                 — navigates back to the login/launch screen.
 */
export function ScanLandingPage({ onSessionCreated, onSignIn }) {
  // step: 'idle' | 'creating' | 'download' | 'error'
  const [step, setStep]               = useState("idle");
  const [error, setError]             = useState("");
  const [sessionData, setSessionData] = useState(null);
  const [viewLink, setViewLink]       = useState("");
  const [linkError, setLinkError]     = useState("");

  // ── New session ──────────────────────────────────────────────────────────────

  const handleStartScan = async () => {
    setError("");
    setStep("creating");
    try {
      // POST /api/v1/anon/sessions — unauthenticated; server rate-limits by IP.
      // Returns { viewToken, scanToken, scanExpiresAt, viewExpiresAt,
      //           dashboardUrl, downloadUrl }
      const data = await api.anon.createSession();
      setSessionData(data);
      setStep("download");
    } catch (e) {
      if (e.status === 429) {
        setError(
          "Too many scan sessions from your IP address. Please wait a few minutes and try again."
        );
      } else if (e.status >= 500) {
        setError("The server is temporarily unavailable. Please try again shortly.");
      } else {
        setError(e.message || "Failed to start scan session. Please try again.");
      }
      setStep("error");
    }
  };

  // ── "View my results" from the download step ─────────────────────────────────

  const handleViewResults = () => {
    // App.jsx's onSessionCreated callback handles pushState + phase switch.
    onSessionCreated(sessionData.viewToken);
  };

  // ── Returning visitor ────────────────────────────────────────────────────────

  const handleViewResultsForm = (e) => {
    e.preventDefault();
    setLinkError("");
    const token = extractViewToken(viewLink);
    if (!token) {
      setLinkError("Please paste a valid /scan/… link or view token.");
      return;
    }
    // Reuse the same callback — AnonScanDashboard will load the existing session.
    onSessionCreated(token);
  };

  const isCreating   = step === "creating";
  const showDownload = step === "download";
  const showError    = step === "error";

  // ── Render ───────────────────────────────────────────────────────────────────

  return (
    <div className="scan-landing">
      {/* ── Header ── */}
      <header className="anon-header" role="banner">
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <div
            className="logo-icon"
            aria-hidden="true"
            style={{ width: 36, height: 36, fontSize: 18, borderRadius: 8 }}
          >
            🔐
          </div>
          <div className="logo-text" style={{ fontSize: "1.05rem", lineHeight: 1 }}>
            CertGuard
          </div>
        </div>
        <button
          className="btn btn-ghost btn-sm"
          onClick={onSignIn}
          style={{ flexShrink: 0 }}
        >
          Sign in →
        </button>
      </header>

      {/* ── Main content ── */}
      <main className="scan-landing-main" id="main-content">

        {/* ── Hero (idle / creating / error states) ── */}
        {!showDownload && (
          <section className="scan-landing-hero" aria-label="Free network scan">
            <div className="scan-landing-badge" aria-hidden="true">
              Free · No account needed
            </div>

            <h1 className="scan-landing-headline">
              Find every expired TLS certificate on your network
            </h1>

            <p className="scan-landing-sub">
              CertGuard scans your private network for TLS endpoints and surfaces
              expired, expiring, and misconfigured certificates — in minutes,
              without creating an account.
            </p>

            {/* CTA */}
            <div className="scan-landing-cta">
              {showError && (
                <div
                  className="alert alert-error"
                  role="alert"
                  aria-live="polite"
                  style={{ marginBottom: "1rem", textAlign: "left" }}
                >
                  <span aria-hidden="true">⚠</span>
                  <span>{error}</span>
                </div>
              )}

              <button
                className="btn btn-primary scan-landing-cta-btn"
                onClick={handleStartScan}
                disabled={isCreating}
                aria-busy={isCreating}
                aria-label={
                  isCreating
                    ? "Starting scan session…"
                    : showError
                    ? "Retry — scan my network"
                    : "Scan my network — free"
                }
              >
                {isCreating ? (
                  <>
                    <Spinner />
                    Starting your session…
                  </>
                ) : showError ? (
                  "Try again"
                ) : (
                  "Scan my network — free"
                )}
              </button>

              <p
                className="scan-landing-cta-hint"
                aria-label="No credit card, no sign-up required"
              >
                No credit card · No sign-up · Takes &lt; 5 min to set up
              </p>
            </div>

            {/* Trust signals */}
            <ul
              className="scan-landing-features"
              aria-label="Why use CertGuard free scan"
            >
              {[
                "No account needed",
                "Your IPs never leave your network",
                "Results deleted after 7 days",
                "Finds expired, expiring & misconfigured certs",
              ].map((text) => (
                <li key={text} className="scan-landing-feature">
                  <span className="scan-landing-feature-icon" aria-hidden="true">
                    ✓
                  </span>
                  <span>{text}</span>
                </li>
              ))}
            </ul>
          </section>
        )}

        {/* ── Download & Run step — shown after session is created ── */}
        {showDownload && (
          <DownloadStep
            sessionData={sessionData}
            onViewResults={handleViewResults}
          />
        )}

        {/* ── Returning visitor — always visible in every state ── */}
        <section
          className="scan-landing-returning"
          aria-label="View existing scan results"
        >
          <p className="scan-landing-returning-label">Already have results?</p>
          <form
            className="scan-landing-returning-form"
            onSubmit={handleViewResultsForm}
            noValidate
          >
            <label htmlFor="scan-view-link" className="scan-landing-sr-only">
              Paste your scan view link or token
            </label>
            <input
              id="scan-view-link"
              type="text"
              className="scan-landing-returning-input"
              placeholder="Paste your /scan/… link or token"
              value={viewLink}
              onChange={(e) => {
                setViewLink(e.target.value);
                setLinkError("");
              }}
              aria-invalid={linkError ? "true" : undefined}
              aria-describedby={linkError ? "scan-view-link-error" : undefined}
              autoComplete="off"
              spellCheck={false}
            />
            <button
              type="submit"
              className="btn btn-secondary btn-sm"
              style={{ flexShrink: 0 }}
            >
              View Results →
            </button>
          </form>
          {linkError && (
            <p
              id="scan-view-link-error"
              className="scan-landing-link-error"
              role="alert"
              aria-live="polite"
            >
              {linkError}
            </p>
          )}
        </section>

      </main>
    </div>
  );
}
