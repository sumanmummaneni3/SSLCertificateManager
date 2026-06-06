import { statusColor, hostTypeColor, fmtDate } from "@/lib/helpers.js";
import { Spinner, Badge, DaysBar } from "@/components/index.js";

export function DashboardView({ dash, targets, onScan, scanning, onAddTarget, me, org }) {
  const showOrgCol = org?.orgType === "MSP" || me?.platformAdmin === true;
  const stats = dash ? [
    { label: "Total Targets",  value: dash.totalTargets, cls: "total"      },
    { label: "Valid",          value: dash.valid,        cls: "valid"      },
    { label: "Expiring Soon",  value: dash.expiring,     cls: "expiring"   },
    { label: "Expired",        value: dash.expired,      cls: "expired"    },
    { label: "Unreachable",    value: dash.unreachable,  cls: "unreachable"},
  ] : [];

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Dashboard</div>
          <div className="page-sub">Certificate monitoring overview</div>
        </div>
        {(me == null || me?.permissions?.canWriteTargets) && (
          <button className="btn btn-primary btn-sm" onClick={onAddTarget}>+ Add Target</button>
        )}
      </div>
      <div className="page-content">
        <div className="stats-grid">
          {stats.map((s) => (
            <div key={s.label} className={`stat-card ${s.cls}`}>
              <div className="stat-label">{s.label}</div>
              <div className="stat-value">{s.value ?? "—"}</div>
            </div>
          ))}
        </div>

        <div className="section-header">
          <div className="section-title">Recent Targets</div>
          <span className="text-muted text-sm">{targets.length} total</span>
        </div>

        {targets.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">🎯</div>
            <div className="empty-title">No targets yet</div>
            <p className="empty-sub">Add a domain or IP address to start monitoring certificates.</p>
            {(me == null || me?.permissions?.canWriteTargets) && (
              <button className="btn btn-primary btn-sm" onClick={onAddTarget} style={{ margin: "0 auto" }}>
                + Add First Target
              </button>
            )}
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  {showOrgCol && <th>Org</th>}
                  <th>Host</th>
                  <th>Type</th>
                  <th>Status</th>
                  <th>Expires</th>
                  <th>Days Left</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {targets.slice(0, 10).map((t) => {
                  const cert = t.latestCertificate;
                  return (
                    <tr key={t.id}>
                      {showOrgCol && <td>{t.orgName || "—"}</td>}
                      <td>
                        <div className="host-cell">{t.host}</div>
                        <div className="mono">:{t.port} {t.isPrivate ? "🔒" : "🌐"}</div>
                      </td>
                      <td><Badge type={hostTypeColor(t.hostType)}>{t.hostType || "—"}</Badge></td>
                      <td>
                        {cert
                          ? <Badge type={statusColor(cert.status)}>{cert.status}</Badge>
                          : <Badge type="unknown">No scan</Badge>}
                      </td>
                      <td className="mono">{cert ? fmtDate(cert.expiryDate) : "—"}</td>
                      <td><DaysBar days={cert?.daysRemaining} /></td>
                      <td>
                        {(me == null || me?.permissions?.canWriteTargets) && (
                          <button className={`scan-btn ${scanning[t.id] ? "scanning" : ""}`}
                            onClick={() => onScan(t)} disabled={scanning[t.id]}>
                            {scanning[t.id] ? <><Spinner /> Scanning</> : "⟳ Scan"}
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}

