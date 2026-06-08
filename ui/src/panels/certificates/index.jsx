import { useState } from "react";
import { statusColor, fmtDate } from "@/lib/helpers.js";
import { Spinner, Badge, DaysBar } from "@/components/index.js";

export function CertsView({ certs, loading, onRefresh, onSelectCert }) {
  const [filter, setFilter] = useState("ALL");
  const filtered = filter === "ALL" ? certs : certs.filter((c) => c.status === filter);

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Certificates</div>
          <div className="page-sub">Full certificate inventory — click a row to view details and manage renewal</div>
        </div>
        <button className="btn btn-secondary btn-sm" onClick={onRefresh}>↻ Refresh</button>
      </div>
      <div className="page-content">
        <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1.5rem", flexWrap: "wrap" }}>
          {["ALL", "VALID", "EXPIRING", "EXPIRED", "UNREACHABLE"].map((s) => (
            <button key={s}
              className={`btn btn-sm ${filter === s ? "btn-primary" : "btn-secondary"}`}
              style={{ width: "auto" }}
              onClick={() => setFilter(s)}>
              {s}
            </button>
          ))}
        </div>

        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading certificates...</span></div>
        ) : filtered.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">📜</div>
            <div className="empty-title">No certificates found</div>
            <p className="empty-sub">Trigger a scan on a target to discover certificates.</p>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Common Name</th>
                  <th>Issuer</th>
                  <th>Status</th>
                  <th>Not Before</th>
                  <th>Expires</th>
                  <th>Days Left</th>
                  <th>Scanned</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((c) => (
                  <tr
                    key={c.id}
                    className={onSelectCert ? "cert-row-clickable" : ""}
                    onClick={() => onSelectCert && onSelectCert(c.id)}
                    onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && onSelectCert && onSelectCert(c.id)}
                    tabIndex={onSelectCert ? 0 : undefined}
                    role={onSelectCert ? "button" : undefined}
                    aria-label={onSelectCert ? `View details for ${c.commonName}` : undefined}
                  >
                    <td className="host-cell">{c.commonName}</td>
                    <td className="mono" style={{ maxWidth: 180, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                      {c.issuer?.split(",")[0]?.replace("CN=", "") || c.issuer}
                    </td>
                    <td><Badge type={statusColor(c.status)}>{c.status}</Badge></td>
                    <td className="mono">{fmtDate(c.notBefore)}</td>
                    <td className="mono">{fmtDate(c.expiryDate)}</td>
                    <td><DaysBar days={c.daysRemaining} /></td>
                    <td className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>
                      {fmtDate(c.scannedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}
