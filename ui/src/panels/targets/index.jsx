import { statusColor, hostTypeColor, fmtDate } from "@/lib/helpers.js";
import { Spinner, Badge, DaysBar } from "@/components/index.js";

export function TargetsView({ targets, onScan, scanning, onAdd, onDelete, onEdit, onRefresh, me, org }) {
  const canWrite = me == null || me?.permissions?.canWriteTargets;
  const scansBlocked = me?.permissions?.scansBlocked === true;
  const showOrgCol = org?.orgType === "MSP" || me?.platformAdmin === true;
  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Targets</div>
          <div className="page-sub">Manage your monitored endpoints</div>
        </div>
        <div style={{ display: "flex", gap: "0.5rem" }}>
          <button className="btn btn-secondary btn-sm" onClick={onRefresh}>↻ Refresh</button>
          {canWrite && (
            <button
              className="btn btn-primary btn-sm"
              onClick={scansBlocked ? undefined : onAdd}
              disabled={scansBlocked}
              title={scansBlocked ? "Scans disabled — subscription suspended" : undefined}
            >
              + Add Target
            </button>
          )}
        </div>
      </div>
      <div className="page-content">
        {targets.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">🎯</div>
            <div className="empty-title">No targets</div>
            <p className="empty-sub">Add your first endpoint to start monitoring.</p>
            {canWrite && (
              <button className="btn btn-primary btn-sm" onClick={onAdd} style={{ margin: "0 auto" }}>+ Add Target</button>
            )}
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  {showOrgCol && <th>Org</th>}
                  <th>Host</th>
                  <th>Port</th>
                  <th>Type</th>
                  <th>Visibility</th>
                  <th>Cert Status</th>
                  <th>Expires</th>
                  <th>Last Scan</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {targets.map((t) => {
                  const cert = t.latestCertificate;
                  return (
                    <tr key={t.id}>
                      {showOrgCol && <td><span className="badge badge-domain">{t.orgName || "—"}</span></td>}
                      <td>
                        <div className="host-cell">{t.host}</div>
                        {t.description && <div className="mono">{t.description}</div>}
                      </td>
                      <td className="mono">{t.port}</td>
                      <td><Badge type={hostTypeColor(t.hostType)}>{t.hostType || "—"}</Badge></td>
                      <td>
                        <Badge type={t.isPrivate ? "private" : "public"}>
                          {t.isPrivate ? "🔒 Private" : "🌐 Public"}
                        </Badge>
                        {t.enabled === false && (
                          <Badge type="unknown" style={{ marginLeft: 4 }}>Disabled</Badge>
                        )}
                        {t.isPrivate && t.agentName && (
                          <div className="mono" style={{ fontSize: "0.7rem", color: "var(--muted)", marginTop: 2 }}>
                            {t.agentName}
                          </div>
                        )}
                        {t.isPrivate && !t.agentName && (
                          <div className="mono" style={{ fontSize: "0.7rem", color: "var(--red)", marginTop: 2 }}>
                            No agent
                          </div>
                        )}
                      </td>
                      <td>
                        {cert
                          ? <Badge type={statusColor(cert.status)}>{cert.status}</Badge>
                          : <Badge type="unknown">No scan</Badge>}
                      </td>
                      <td className="mono">{cert ? fmtDate(cert.expiryDate) : "—"}</td>
                      <td className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>
                        {t.lastScannedAt ? fmtDate(t.lastScannedAt) : "Never"}
                      </td>
                      <td>
                        <div className="row-actions">
                          {canWrite && (
                            <button className={`scan-btn ${scanning[t.id] ? "scanning" : ""}`}
                              onClick={() => onScan(t)} disabled={scanning[t.id] || scansBlocked}
                              title={scansBlocked ? "Scans disabled — subscription suspended" : (t.isPrivate ? "Queue scan job for agent" : "Trigger scan")}>
                              {scanning[t.id] ? <Spinner /> : "⟳"}
                            </button>
                          )}
                          {canWrite && (
                            <button className="scan-btn" style={{ color: "var(--muted)", borderColor: "rgba(90,96,112,0.3)" }}
                              onClick={() => onEdit(t)} title="Edit target">✎</button>
                          )}
                          {canWrite && (
                            <button className="scan-btn" style={{ color: "var(--red)", borderColor: "rgba(255,82,82,0.3)" }}
                              onClick={() => onDelete(t.id)} title="Delete target">✕</button>
                          )}
                        </div>
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
