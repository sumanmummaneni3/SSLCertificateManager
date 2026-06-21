import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function MspDashboardView({ token, me, onViewClientTargets }) {
  const [dash, setDash]     = useState(null);
  const [loading, setLoading] = useState(true);
  const upgradePending = me?.permissions?.mspUpgradePending === true;

  useEffect(() => {
    if (upgradePending) return;
    api.msp.getDashboard(token)
      .then(setDash)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [token, upgradePending]);

  if (upgradePending) {
    return (
      <>
        <div className="page-header"><div className="page-title">MSP Dashboard</div></div>
        <div className="empty" style={{ padding: "4rem 2rem" }}>
          <div className="empty-icon" aria-hidden="true">⬡</div>
          <div className="empty-title">MSP Upgrade Pending</div>
          <p className="empty-sub">Your MSP upgrade request is under review by our team. You&apos;ll receive an email once it&apos;s approved and MSP features are unlocked.</p>
        </div>
      </>
    );
  }

  const stats = dash ? [
    { label: "Child Orgs",     value: dash.childOrgCount,  cls: "total"       },
    { label: "Total Targets",  value: dash.totalTargets,   cls: "total"       },
    { label: "Valid",          value: dash.valid,           cls: "valid"       },
    { label: "Expiring Soon",  value: dash.expiring,        cls: "expiring"    },
    { label: "Expired",        value: dash.expired,         cls: "expired"     },
    { label: "Active Agents",  value: dash.totalAgents,     cls: "unreachable" },
  ] : [];

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">MSP Dashboard</div>
          <div className="page-sub">Aggregated view across all client organisations</div>
        </div>
      </div>
      <div className="page-content">
        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading MSP dashboard...</span></div>
        ) : !dash ? (
          <div className="empty">
            <div className="empty-icon" aria-hidden="true">◇</div>
            <div className="empty-title">MSP dashboard unavailable</div>
            <p className="empty-sub">Could not load dashboard data. MSP features may not be active on your account.</p>
          </div>
        ) : (
          <>
            <div className="stats-grid">
              {stats.map((s) => (
                <div key={s.label} className={`stat-card ${s.cls}`}>
                  <div className="stat-label">{s.label}</div>
                  <div className="stat-value">{s.value ?? "—"}</div>
                </div>
              ))}
            </div>

            <div className="section-header">
              <div className="section-title">Client Organisations</div>
              <span className="text-muted text-sm">{(dash.perOrg || []).length} clients</span>
            </div>

            {(dash.perOrg || []).length === 0 ? (
              <div className="empty">
                <div className="empty-icon" aria-hidden="true">⬡</div>
                <div className="empty-title">No client organisations</div>
                <p className="empty-sub">Add client orgs from the Client Orgs section.</p>
              </div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Org Name</th>
                      <th>Targets</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {(dash.perOrg || []).map((o) => (
                      <tr key={o.orgId}>
                        <td className="host-cell">{o.orgName}</td>
                        <td className="mono">{o.targetCount ?? 0}</td>
                        <td>
                          <button
                            className="scan-btn"
                            style={{ color: "var(--accent)", borderColor: "rgba(0,212,255,0.3)" }}
                            onClick={() => onViewClientTargets?.(o.orgId, o.orgName)}
                            aria-label={`View targets for ${o.orgName}`}
                          >
                            View Targets
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </div>
    </>
  );
}
