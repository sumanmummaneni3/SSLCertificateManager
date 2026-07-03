/**
 * ScannerPoolView — RFC 0013 §9
 *
 * Platform-admin panel showing the cloud scanner fleet and the PUBLIC_POOL
 * job backlog. All data comes from GET /api/v1/admin/scanner-pool.
 *
 * Degrades gracefully when the endpoint is not yet deployed (404 → shows
 * "coming soon" notice identical to PlatformOrgsView's api-unavailable state).
 *
 * Response shape (ratified contract, ScannerPoolResponse.ScannerInfo):
 *   {
 *     scanners: [{ id, name, status, lastSeenAt, totalJobsCompleted, jobsClaimedLastHour }],
 *     backlog:  { pendingCount, oldestPendingAgeMinutes }
 *   }
 */
import { useState, useEffect, useCallback } from "react";
import { api } from "@/lib/api.js";
import { fmtRelative } from "@/lib/helpers.js";
import { Spinner, Badge } from "@/components/index.js";

// Map agent status → badge type (same palette as AgentsView)
function scannerStatusBadge(s) {
  return (
    { ACTIVE: "active", PENDING: "pending", OFFLINE: "offline", REVOKED: "revoked" }[s] || "pending"
  );
}

/**
 * Formats a minute count as a human-readable age string.
 *  0–59  min → "{n} min"
 *  60+   min → "{n}h {m}m"
 */
function fmtMinutes(minutes) {
  if (!Number.isFinite(minutes) || minutes < 0) return "—";
  if (minutes < 60) return `${minutes} min`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

export function ScannerPoolView({ token, toast }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [apiUnavailable, setApiUnavailable] = useState(false);

  const load = useCallback(async () => {
    try {
      const result = await api.admin.getScannerPool(token);
      setData(result);
      setApiUnavailable(false);
    } catch (e) {
      if (e.status === 404 || e.message?.includes("404")) {
        setApiUnavailable(true);
      } else {
        toast("Failed to load scanner pool: " + e.message, "error");
      }
    } finally {
      setLoading(false);
    }
  }, [token, toast]);

  useEffect(() => { load(); }, [load]);

  // Auto-refresh every 30 s while mounted — pool metrics change frequently
  useEffect(() => {
    const id = setInterval(load, 30_000);
    return () => clearInterval(id);
  }, [load]);

  if (loading) {
    return (
      <div className="loading-center" style={{ minHeight: "60vh" }}>
        <Spinner lg /><span>Loading scanner pool...</span>
      </div>
    );
  }

  if (apiUnavailable) {
    return (
      <>
        <div className="page-header">
          <div>
            <div className="page-title">Scanner Pool</div>
            <div className="page-sub">Platform cloud scanner fleet and job backlog</div>
          </div>
        </div>
        <div className="page-content">
          <div className="admin-api-unavailable">
            <div style={{ fontSize: "2rem", marginBottom: "1rem", opacity: 0.4 }}>⬡</div>
            <div style={{ fontFamily: "var(--font-head)", fontSize: "1rem", color: "var(--text)", marginBottom: "0.5rem" }}>
              Scanner Pool API not yet available
            </div>
            <div style={{ fontSize: "0.82rem" }}>
              The scanner pool endpoints are being deployed. This panel will populate automatically once live (RFC 0013).
            </div>
          </div>
        </div>
      </>
    );
  }

  const scanners = data?.scanners ?? [];
  const backlog = data?.backlog ?? {};
  const pendingCount = backlog.pendingCount ?? 0;
  const oldestAge = backlog.oldestPendingAgeMinutes;
  const backlogDelayed = Number.isFinite(oldestAge) && oldestAge > 15;

  const activeScanners = scanners.filter((s) => s.status === "ACTIVE").length;
  const offlineScanners = scanners.filter((s) => s.status === "OFFLINE").length;

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Scanner Pool</div>
          <div className="page-sub">
            Platform cloud scanner fleet — {scanners.length} scanner{scanners.length !== 1 ? "s" : ""}
            {activeScanners > 0 && `, ${activeScanners} active`}
            {offlineScanners > 0 && `, ${offlineScanners} offline`}
          </div>
        </div>
        <button className="btn btn-secondary btn-sm" onClick={load}>
          Refresh
        </button>
      </div>

      <div className="page-content">
        {/* Backlog summary cards */}
        <div className="stats-grid" style={{ marginBottom: "2rem" }}>
          <div className={`stat-card ${pendingCount === 0 ? "valid" : backlogDelayed ? "expired" : "expiring"}`}>
            <div className="stat-label">Pending Jobs</div>
            <div className="stat-value">{pendingCount}</div>
          </div>
          <div className={`stat-card ${backlogDelayed ? "expired" : "valid"}`}>
            <div className="stat-label">Oldest Pending</div>
            <div className="stat-value" style={{ fontSize: "1.4rem" }}>
              {Number.isFinite(oldestAge) ? fmtMinutes(oldestAge) : "—"}
            </div>
          </div>
          <div className="stat-card total">
            <div className="stat-label">Active Scanners</div>
            <div className="stat-value">{activeScanners}</div>
          </div>
        </div>

        {/* Backlog health alert */}
        {backlogDelayed && (
          <div className="alert alert-warning" role="alert" style={{ marginBottom: "1.5rem" }}>
            <span aria-hidden="true" style={{ fontSize: "1rem" }}>!</span>
            <div>
              <strong>Pool backlog stalled</strong> — oldest pending job is {fmtMinutes(oldestAge)} old.
              Check scanner fleet health; no scanner may be polling (expected claim within 15 min).
            </div>
          </div>
        )}

        {/* Scanner fleet table */}
        {scanners.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">⬡</div>
            <div className="empty-title">No scanner agents registered</div>
            <p className="empty-sub">
              Deploy platform scanner agents (agent.mode=AUTHENTICATED, agent.scanner-pool=true)
              to handle public-target scan jobs.
            </p>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Scanner Name</th>
                  <th>Status</th>
                  <th>Last Seen</th>
                  <th>Jobs / Hour</th>
                  <th>Jobs Total</th>
                </tr>
              </thead>
              <tbody>
                {scanners.map((s) => (
                  <tr key={s.id}>
                    <td>
                      <div className="host-cell">{s.name}</div>
                    </td>
                    <td>
                      <Badge type={scannerStatusBadge(s.status)}>{s.status}</Badge>
                    </td>
                    <td className="mono" style={{ color: "var(--muted)", fontSize: "0.75rem" }}>
                      {fmtRelative(s.lastSeenAt)}
                    </td>
                    <td className="mono">{s.jobsClaimedLastHour ?? "—"}</td>
                    <td className="mono">{s.totalJobsCompleted ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="alert alert-info" style={{ marginTop: "1.5rem" }}>
          <span>i</span>
          <div style={{ fontSize: "0.78rem" }}>
            Platform scanner agents are enrolled in the platform organization and are not visible
            to customer orgs. Scanner IPs should be published in docs so customers can allowlist them.
            Pool metrics refresh every 30 seconds.
          </div>
        </div>
      </div>
    </>
  );
}
