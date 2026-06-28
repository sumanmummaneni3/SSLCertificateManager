import { useState, useEffect } from "react";
import { fmtRelative } from "@/lib/helpers.js";
import { Spinner, Badge } from "@/components/index.js";
import { NetworkScanModal } from "./NetworkScanModal.jsx";

// Map network scan status → existing badge CSS type
const SCAN_STATUS_COLOR = {
  PENDING:     "pending",
  IN_PROGRESS: "pending",
  COMPLETE:    "active",
  FAILED:      "revoked",
  CANCELLED:   "revoked",
};

export function NetworkScansView({
  scans,
  loading,
  token,
  org,
  me,
  toast,
  onRefresh,
  onSelectScan,
  agents,
}) {
  const [showModal, setShowModal] = useState(false);
  const canWrite = me == null || me?.permissions?.canWriteAgents === true;

  // Poll every 5 s while any scan is PENDING or IN_PROGRESS
  useEffect(() => {
    const needsPoll = scans.some(
      (s) => s.status === "PENDING" || s.status === "IN_PROGRESS"
    );
    if (!needsPoll) return;
    const id = setInterval(onRefresh, 5000);
    return () => clearInterval(id);
  }, [scans, onRefresh]);

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Network Scans</div>
          <div className="page-sub">
            TCP port sweeps and TLS certificate discovery across CIDR ranges
          </div>
        </div>
        <div style={{ display: "flex", gap: "0.5rem" }}>
          <button
            className="btn btn-secondary btn-sm"
            onClick={onRefresh}
            aria-label="Refresh network scans"
          >
            ↻ Refresh
          </button>
          {canWrite && (
            <button
              className="btn btn-primary btn-sm"
              onClick={() => setShowModal(true)}
            >
              + Scan Network
            </button>
          )}
        </div>
      </div>

      <div className="page-content">
        {loading ? (
          <div className="loading-center">
            <Spinner lg />
            <span>Loading network scans…</span>
          </div>
        ) : scans.length === 0 ? (
          <div className="empty">
            <div className="empty-icon" aria-hidden="true">◮</div>
            <div className="empty-title">No network scans yet</div>
            <p className="empty-sub">
              {canWrite
                ? 'Click "+ Scan Network" to discover TLS endpoints on your network.'
                : "No network scans have been run yet."}
            </p>
            {canWrite && (
              <button
                className="btn btn-primary btn-sm"
                onClick={() => setShowModal(true)}
                style={{ margin: "0 auto" }}
              >
                + Scan Network
              </button>
            )}
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>CIDR</th>
                  <th>Profile</th>
                  <th>Status</th>
                  <th>TLS Found</th>
                  <th>Hosts Scanned</th>
                  <th>Started</th>
                  <th>Agent</th>
                </tr>
              </thead>
              <tbody>
                {scans.map((scan) => (
                  <tr
                    key={scan.id}
                    className="cert-row-clickable"
                    role="button"
                    tabIndex={0}
                    aria-label={`View scan details for ${scan.cidr}`}
                    onClick={() => onSelectScan(scan.id)}
                    onKeyDown={(e) =>
                      (e.key === "Enter" || e.key === " ") &&
                      onSelectScan(scan.id)
                    }
                  >
                    <td className="host-cell mono">{scan.cidr}</td>
                    <td>
                      <Badge type="domain">{scan.portProfile}</Badge>
                    </td>
                    <td>
                      <Badge
                        type={SCAN_STATUS_COLOR[scan.status] || "unknown"}
                      >
                        {scan.status}
                      </Badge>
                    </td>
                    <td className="mono">{scan.tlsFoundCount ?? "—"}</td>
                    <td
                      className="mono"
                      style={{ color: "var(--muted)", fontSize: "0.75rem" }}
                    >
                      {scan.hostsTotal != null
                        ? `${scan.hostsScanned ?? 0} / ${scan.hostsTotal}`
                        : "—"}
                    </td>
                    <td
                      className="mono"
                      style={{ color: "var(--muted)", fontSize: "0.75rem" }}
                    >
                      {fmtRelative(scan.createdAt)}
                    </td>
                    <td style={{ color: "var(--muted)", fontSize: "0.75rem" }}>
                      {scan.agentName || "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showModal && (
        <NetworkScanModal
          token={token}
          orgId={org.id}
          agents={agents}
          toast={toast}
          onClose={() => setShowModal(false)}
          onCreated={() => {
            setShowModal(false);
            onRefresh();
          }}
        />
      )}
    </>
  );
}
