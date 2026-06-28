import { useState, useEffect, useRef } from "react";
import { api } from "@/lib/api.js";
import { Spinner, ConfirmModal } from "@/components/index.js";
import { SubnetCard } from "@/panels/network-scans/SubnetCard.jsx";
import { DeviceList } from "@/panels/network-scans/DeviceList.jsx";

// ── Sub-components ────────────────────────────────────────────────────────────

function AnonHeader({ onSignUp }) {
  return (
    <header className="anon-header">
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <div
          className="logo-icon"
          aria-hidden="true"
          style={{ width: 36, height: 36, fontSize: 18, borderRadius: 8 }}
        >
          🔐
        </div>
        <div
          className="logo-text"
          style={{ fontSize: "1.05rem", lineHeight: 1 }}
        >
          CertGuard
        </div>
      </div>
      <button
        className="btn btn-primary btn-sm"
        onClick={onSignUp}
        style={{ flexShrink: 0 }}
      >
        Sign Up Free
      </button>
    </header>
  );
}

function SummaryChip({ label, value, color }) {
  return (
    <div className="summary-chip">
      <div className="summary-chip-value" style={{ color: color || "var(--accent)" }}>
        {value}
      </div>
      <div className="summary-chip-label">{label}</div>
    </div>
  );
}

// ── Expired / deleted / claimed state ─────────────────────────────────────────

function GoneState({ status, deleted, onSignUp }) {
  let heading, body, cta;

  if (deleted) {
    heading = "Your scan data has been deleted.";
    body    = "Your anonymous scan results have been permanently removed.";
    cta     = "Create Free Account";
  } else if (status === "CLAIMED") {
    heading = "This scan has already been claimed by an account.";
    body    = "Sign in to see the full results in your account.";
    cta     = "Sign In";
  } else {
    heading = "This scan link has expired or doesn't exist.";
    body    = "Run a new scan to discover TLS endpoints on your network.";
    cta     = "Create Free Account";
  }

  return (
    <div
      style={{
        flex:           1,
        display:        "flex",
        flexDirection:  "column",
        alignItems:     "center",
        justifyContent: "center",
        textAlign:      "center",
        padding:        "3rem 2rem",
      }}
    >
      <div
        style={{
          fontSize:     "3rem",
          opacity:      0.25,
          marginBottom: "1.25rem",
          lineHeight:   1,
        }}
        aria-hidden="true"
      >
        ◮
      </div>
      <h1
        style={{
          fontFamily:   "var(--font-head)",
          fontSize:     "1.3rem",
          fontWeight:   700,
          marginBottom: "0.75rem",
        }}
      >
        {heading}
      </h1>
      <p
        style={{
          color:        "var(--muted)",
          fontSize:     "0.85rem",
          marginBottom: "2rem",
          maxWidth:     400,
          lineHeight:   1.6,
        }}
      >
        {body}
      </p>
      <button
        className="btn btn-primary"
        style={{ width: "auto", minWidth: 200 }}
        onClick={onSignUp}
      >
        {cta}
      </button>
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

/**
 * AnonScanDashboard — fully public, no auth headers required.
 *
 * Renders three states:
 *   ACTIVE       → "your scan is running" spinner
 *   SCAN_COMPLETE → full results with summary chips, subnets, devices, CTA
 *   EXPIRED/CLAIMED/DELETED/error → gone state
 *
 * Props:
 *   viewToken: string
 *   onSignUp: () => void  — navigates to login/register phase
 */
export function AnonScanDashboard({ viewToken, onSignUp }) {
  const [session, setSession]                   = useState(null);
  const [loading, setLoading]                   = useState(true);
  const [deleted, setDeleted]                   = useState(false);
  const [deleting, setDeleting]                 = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);

  // Use a ref so the poll interval closure always has the latest status
  const sessionStatusRef = useRef(null);

  // Initial fetch
  useEffect(() => {
    let cancelled = false;

    api.anon
      .getSession(viewToken)
      .then((data) => {
        if (cancelled) return;
        setSession(data);
        sessionStatusRef.current = data?.status;
      })
      .catch(() => {
        if (cancelled) return;
        setSession({ status: "EXPIRED" });
        sessionStatusRef.current = "EXPIRED";
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [viewToken]);

  // Poll every 5 s while ACTIVE
  useEffect(() => {
    if (!session || session.status !== "ACTIVE") return;

    const id = setInterval(async () => {
      try {
        const data = await api.anon.getSession(viewToken);
        setSession(data);
        sessionStatusRef.current = data?.status;
      } catch {
        // Non-fatal — keep polling; the interval will stop naturally once
        // status transitions out of ACTIVE on the next successful response.
      }
    }, 5000);

    return () => clearInterval(id);
  }, [session?.status, viewToken]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleDeleteConfirm = async () => {
    setDeleting(true);
    try {
      await api.anon.deleteSession(viewToken);
    } catch {
      // Treat any response (including 404) as a successful deletion for UX
    } finally {
      setDeleting(false);
      setShowDeleteConfirm(false);
      setDeleted(true);
    }
  };

  // ── Shared page wrapper ────────────────────────────────────────────────────
  const wrap = (children, fullCenter = false) => (
    <div
      style={{
        minHeight:      "100vh",
        display:        "flex",
        flexDirection:  "column",
      }}
    >
      <AnonHeader onSignUp={onSignUp} />
      <div
        style={{
          flex:      1,
          display:   "flex",
          flexDirection: "column",
          ...(fullCenter
            ? { alignItems: "center", justifyContent: "center" }
            : {}),
        }}
      >
        {children}
      </div>
    </div>
  );

  // ── Loading ────────────────────────────────────────────────────────────────
  if (loading) {
    return wrap(
      <div className="loading-center" style={{ flex: 1 }}>
        <Spinner lg />
        <span>Loading your scan results…</span>
      </div>,
      true
    );
  }

  // ── Gone (expired / claimed / deleted / error) ─────────────────────────────
  if (
    deleted ||
    !session ||
    session.status === "EXPIRED" ||
    session.status === "DELETED" ||
    session.status === "CLAIMED"
  ) {
    return wrap(
      <GoneState
        status={session?.status}
        deleted={deleted}
        onSignUp={onSignUp}
      />
    );
  }

  // ── ACTIVE — scan still running ────────────────────────────────────────────
  if (session.status === "ACTIVE") {
    return wrap(
      <div
        style={{
          flex:           1,
          display:        "flex",
          alignItems:     "center",
          justifyContent: "center",
          padding:        "2rem",
        }}
      >
        <div
          style={{
            background:   "var(--surface)",
            border:       "1px solid var(--border2)",
            borderRadius: 16,
            padding:      "3rem 2.5rem",
            textAlign:    "center",
            maxWidth:     480,
            width:        "100%",
          }}
          role="status"
          aria-live="polite"
          aria-label="Scan in progress"
        >
          <Spinner lg />
          <h1
            style={{
              fontFamily:   "var(--font-head)",
              fontSize:     "1.3rem",
              fontWeight:   700,
              marginTop:    "1.5rem",
              marginBottom: "0.5rem",
            }}
          >
            Your network scan is running…
          </h1>
          <p
            style={{
              color:     "var(--muted)",
              fontSize:  "0.85rem",
              lineHeight: 1.6,
            }}
          >
            We&apos;re discovering subnets and checking for SSL certificates.
            This page will update automatically.
          </p>
        </div>
      </div>
    );
  }

  // ── SCAN_COMPLETE — results ────────────────────────────────────────────────
  const { summary, subnets, devices } = session;

  return wrap(
    <>
      <div className="anon-content">
        <h1
          style={{
            fontFamily:   "var(--font-head)",
            fontSize:     "1.6rem",
            fontWeight:   700,
            letterSpacing: "-0.02em",
            marginBottom: "1.5rem",
          }}
        >
          Network Scan Results
        </h1>

        {/* ── Summary chips ── */}
        <div className="summary-chips">
          <SummaryChip
            label="Subnets"
            value={summary?.subnetCount ?? 0}
            color="var(--accent)"
          />
          <SummaryChip
            label="Devices"
            value={summary?.deviceCount ?? 0}
            color="var(--text)"
          />
          <SummaryChip
            label="TLS Found"
            value={summary?.tlsFoundCount ?? 0}
            color="var(--green)"
          />
          <SummaryChip
            label="Routers"
            value={summary?.routerCount ?? 0}
            color="var(--yellow)"
          />
          {(summary?.serverCount ?? 0) > 0 && (
            <SummaryChip
              label="Servers"
              value={summary.serverCount}
              color="var(--muted)"
            />
          )}
        </div>

        {/* ── Discovered subnets ── */}
        {subnets && subnets.length > 0 && (
          <section style={{ marginBottom: "2rem" }}>
            <h2
              className="section-title"
              style={{ marginBottom: "0.75rem", fontSize: "0.9rem" }}
            >
              Discovered Subnets
            </h2>
            <div style={{ display: "flex", flexDirection: "column", gap: "0.5rem" }}>
              {subnets.map((s) => (
                <SubnetCard key={s.id} subnet={s} />
              ))}
            </div>
          </section>
        )}

        {/* ── Devices ── */}
        {devices && devices.length > 0 && (
          <section style={{ marginBottom: "2rem" }}>
            <h2
              className="section-title"
              style={{ marginBottom: "0.75rem", fontSize: "0.9rem" }}
            >
              Devices Found
            </h2>
            <DeviceList devices={devices} />
          </section>
        )}

        {/* ── CTA card ── */}
        <div className="anon-cta-card">
          <div className="anon-cta-title">
            Want to see full details and run a deep scan?
          </div>
          <p className="anon-cta-sub">Sign up free — takes 30 seconds.</p>
          <button
            className="btn btn-primary"
            style={{ width: "auto", minWidth: 220, margin: "0 auto 0.75rem" }}
            onClick={onSignUp}
          >
            Create Free Account
          </button>
          <div style={{ fontSize: "0.78rem", color: "var(--muted)" }}>
            Already have an account?{" "}
            <button
              className="btn btn-ghost"
              style={{
                padding:    0,
                fontSize:   "0.78rem",
                width:      "auto",
                display:    "inline",
                lineHeight: "inherit",
              }}
              onClick={onSignUp}
            >
              Sign In
            </button>
          </div>
        </div>

        {/* ── GDPR delete link ── */}
        <div style={{ textAlign: "center", paddingBottom: "2rem" }}>
          <button
            className="btn btn-ghost"
            style={{
              fontSize: "0.75rem",
              color:    "var(--muted)",
              width:    "auto",
            }}
            onClick={() => setShowDeleteConfirm(true)}
          >
            Delete this scan
          </button>
        </div>
      </div>

      {/* ── Delete confirmation modal ── */}
      {showDeleteConfirm && (
        <ConfirmModal
          title="Delete Scan?"
          body="This will permanently delete your anonymous scan results. This cannot be undone."
          confirmLabel="Delete"
          cancelLabel="Cancel"
          onConfirm={handleDeleteConfirm}
          onCancel={() => setShowDeleteConfirm(false)}
          loading={deleting}
          role="alertdialog"
          labelledBy="del-scan-confirm-title"
        />
      )}
    </>
  );
}
