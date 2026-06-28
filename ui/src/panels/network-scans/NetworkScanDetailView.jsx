import { useState, useEffect, useCallback } from "react";
import { api } from "@/lib/api.js";
import { fmtRelative } from "@/lib/helpers.js";
import { Spinner, Badge } from "@/components/index.js";
import { DiscoveredEndpointTable } from "./DiscoveredEndpointTable.jsx";

// ── Constants ─────────────────────────────────────────────────────────────────

const SCAN_STATUS_COLOR = {
  PENDING:     "pending",
  IN_PROGRESS: "pending",
  COMPLETE:    "active",
  FAILED:      "revoked",
  CANCELLED:   "revoked",
};

// ── Sub-components ────────────────────────────────────────────────────────────

function SummaryChip({ label, value, color }) {
  return (
    <div
      style={{
        background:   "var(--surface)",
        border:       "1px solid var(--border)",
        borderRadius: 12,
        padding:      "0.75rem 1rem",
        minWidth:     110,
        textAlign:    "center",
      }}
    >
      <div
        style={{
          fontFamily:  "var(--font-head)",
          fontSize:    "1.5rem",
          fontWeight:  800,
          color:       color || "var(--text)",
          lineHeight:  1,
        }}
      >
        {value}
      </div>
      <div
        style={{
          fontSize:       "0.65rem",
          color:          "var(--muted)",
          textTransform:  "uppercase",
          letterSpacing:  "0.08em",
          marginTop:      5,
        }}
      >
        {label}
      </div>
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

export function NetworkScanDetailView({ scanId, orgId, token, toast, onBack }) {
  const [scan, setScan]       = useState(null);
  const [loading, setLoading] = useState(true);

  const fetchScan = useCallback(async () => {
    try {
      const data = await api.networkScans.get(token, orgId, scanId);
      setScan(data);
    } catch (e) {
      toast("Failed to load scan: " + e.message, "error");
    } finally {
      setLoading(false);
    }
  }, [token, orgId, scanId, toast]);

  // Initial load
  useEffect(() => {
    fetchScan();
  }, [fetchScan]);

  // Poll every 5 s while PENDING or IN_PROGRESS
  useEffect(() => {
    if (!scan) return;
    if (scan.status !== "PENDING" && scan.status !== "IN_PROGRESS") return;
    const id = setInterval(fetchScan, 5000);
    return () => clearInterval(id);
  }, [scan?.status, fetchScan]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Loading shell ──────────────────────────────────────────────────────────
  if (loading) {
    return (
      <div className="page-content">
        <div className="loading-center">
          <Spinner lg />
          <span>Loading scan details…</span>
        </div>
      </div>
    );
  }

  if (!scan) {
    return (
      <div className="page-content">
        <button
          className="btn btn-secondary btn-sm"
          style={{ width: "auto" }}
          onClick={onBack}
        >
          ← Back to Network Scans
        </button>
        <div className="empty" style={{ marginTop: "2rem" }}>
          <div className="empty-title">Scan not found</div>
        </div>
      </div>
    );
  }

  const isActive  = scan.status === "PENDING" || scan.status === "IN_PROGRESS";
  const isDone    = scan.status === "COMPLETE" || scan.status === "FAILED" || scan.status === "CANCELLED";
  const hostsScanned = scan.hostsScanned ?? 0;
  const hostsTotal   = scan.hostsTotal   ?? 0;
  const progress     = hostsTotal > 0 ? Math.round((hostsScanned / hostsTotal) * 100) : 0;
  const hasData      = hostsScanned > 0 || isDone;

  return (
    <>
      {/* ── Page header ── */}
      <div className="page-header">
        <div>
          <button
            className="btn btn-ghost"
            style={{ padding: "0 0 0.5rem", fontSize: "0.82rem", width: "auto" }}
            onClick={onBack}
          >
            ← Back to Network Scans
          </button>
          <div className="page-title" style={{ marginTop: "0.25rem" }}>
            {scan.cidr}
          </div>
          <div className="page-sub">
            Agent:{" "}
            <span style={{ color: "var(--text)" }}>
              {scan.agentName || scan.agentId}
            </span>
            {" "}·{" "}
            Started: {fmtRelative(scan.createdAt)}
          </div>
        </div>
        <div
          style={{
            display:    "flex",
            alignItems: "center",
            gap:        "0.75rem",
            flexShrink: 0,
          }}
        >
          <Badge type={SCAN_STATUS_COLOR[scan.status] || "unknown"}>
            {scan.status}
          </Badge>
          <Badge type="domain">{scan.portProfile}</Badge>
        </div>
      </div>

      <div className="page-content">
        {/* ── Progress bar (only while active) ── */}
        {isActive && (
          <div
            style={{
              background:   "var(--surface)",
              border:       "1px solid var(--border)",
              borderRadius: 12,
              padding:      "1.25rem",
              marginBottom: "1.5rem",
            }}
            role="status"
            aria-label={`Scan progress: ${progress}%`}
          >
            <div
              style={{
                display:         "flex",
                justifyContent:  "space-between",
                alignItems:      "center",
                marginBottom:    "0.5rem",
              }}
            >
              <span style={{ fontSize: "0.85rem", fontWeight: 500 }}>
                {scan.status === "PENDING"
                  ? "Waiting for agent…"
                  : `Scanning ${scan.cidr}`}
              </span>
              <span
                style={{
                  fontSize:   "0.75rem",
                  color:      "var(--muted)",
                  fontFamily: "var(--font-mono)",
                }}
              >
                {hostsScanned} / {hostsTotal || "?"} hosts
              </span>
            </div>

            <div
              className="scan-progress-track"
              aria-hidden="true"
            >
              <div
                className="scan-progress-fill"
                style={{ width: `${progress}%` }}
              />
            </div>

            <div
              style={{
                fontSize:  "0.72rem",
                color:     "var(--muted)",
                marginTop: "0.4rem",
              }}
            >
              {progress}% complete
            </div>
          </div>
        )}

        {/* ── Summary chips ── */}
        {hasData && (
          <div
            style={{
              display:      "flex",
              gap:          "0.75rem",
              flexWrap:     "wrap",
              marginBottom: "1.5rem",
            }}
          >
            <SummaryChip
              label="TLS Found"
              value={scan.tlsFoundCount ?? 0}
              color="var(--green)"
            />
            <SummaryChip
              label="Open Ports"
              value={scan.openPortCount ?? 0}
              color="var(--accent)"
            />
            <SummaryChip
              label="Hosts Scanned"
              value={hostsScanned}
              color="var(--text)"
            />
            <SummaryChip
              label="Total Hosts"
              value={hostsTotal || "—"}
              color="var(--muted)"
            />
          </div>
        )}

        {/* ── Error message ── */}
        {scan.errorMessage && (
          <div
            className="alert alert-error"
            role="alert"
            style={{ marginBottom: "1.5rem" }}
          >
            {scan.errorMessage}
          </div>
        )}

        {/* ── Endpoint table (scan complete/failed/cancelled) ── */}
        {isDone && (
          <div>
            <div className="section-header" style={{ marginBottom: "1rem" }}>
              <div className="section-title">Discovered Endpoints</div>
            </div>
            <DiscoveredEndpointTable
              scanId={scanId}
              orgId={orgId}
              token={token}
            />
          </div>
        )}

        {/* ── In-progress placeholder ── */}
        {isActive && hostsScanned === 0 && (
          <p style={{ color: "var(--muted)", fontSize: "0.82rem" }}>
            Results will appear here once the agent starts reporting data.
          </p>
        )}
      </div>
    </>
  );
}
