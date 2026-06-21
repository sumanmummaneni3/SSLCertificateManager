import { useState } from "react";
import { certBadgeType, certStatusLabel, fmtDate } from "@/lib/helpers.js";
import { Spinner, Badge, DaysBar } from "@/components/index.js";

// Status filter definitions — ordered by severity (revoked first)
const FILTER_STATUSES = [
  { key: "ALL",         label: "All" },
  { key: "REVOKED",     label: "Revoked",     title: "Show only revoked certificates (highest priority)" },
  { key: "INVALID",     label: "Invalid",     title: "Show certificates with invalid or untrusted chains" },
  { key: "EXPIRED",     label: "Expired" },
  { key: "EXPIRING",    label: "Expiring" },
  { key: "VALID",       label: "Valid" },
  { key: "UNREACHABLE", label: "Unreachable" },
];

/** Returns a count or "" (empty string renders nothing) */
function statusCount(certs, status) {
  if (status === "ALL") return certs.length;
  return certs.filter((c) => c.status === status).length;
}

export function CertsView({ certs, loading, onRefresh, onSelectCert }) {
  const [filter, setFilter] = useState("ALL");

  // Apply filter: for REVOKED we also include on-hold certs (onHold=true)
  const filtered = filter === "ALL"
    ? certs
    : certs.filter((c) => c.status === filter);

  const revokedCount  = certs.filter((c) => c.status === "REVOKED").length;
  const invalidCount  = certs.filter((c) => c.status === "INVALID").length;
  const onHoldCount   = certs.filter((c) => c.status === "REVOKED" && c.onHold).length;

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
        {/* Security summary callout when revoked/invalid certs exist */}
        {(revokedCount > 0 || invalidCount > 0) && (
          <div
            role="alert"
            aria-live="polite"
            style={{
              display: "flex",
              alignItems: "flex-start",
              gap: 12,
              padding: "10px 16px",
              marginBottom: "1rem",
              background: "rgba(220,38,38,0.08)",
              border: "1px solid rgba(220,38,38,0.3)",
              borderRadius: "var(--radius)",
              fontSize: "0.82rem",
              color: "#ff6666",
            }}
          >
            <span aria-hidden="true" style={{ flexShrink: 0, fontSize: "1rem" }}>🚫</span>
            <span>
              {revokedCount > 0 && (
                <>
                  <strong>{revokedCount} revoked</strong>
                  {onHoldCount > 0 && ` (${onHoldCount} on hold)`}
                  {invalidCount > 0 ? " · " : " "}
                </>
              )}
              {invalidCount > 0 && (
                <><strong>{invalidCount} invalid chain</strong>{" "}</>
              )}
              certificate{revokedCount + invalidCount !== 1 ? "s" : ""} require attention.
            </span>
          </div>
        )}

        {/* Filter chips */}
        <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1.5rem", flexWrap: "wrap" }} role="group" aria-label="Filter certificates by status">
          {FILTER_STATUSES.map((s) => {
            const count = statusCount(certs, s.key);
            // Don't render zero-count security filters (except ALL/VALID/EXPIRING/EXPIRED)
            const alwaysShow = ["ALL", "VALID", "EXPIRING", "EXPIRED", "UNREACHABLE"].includes(s.key);
            if (!alwaysShow && count === 0) return null;
            return (
              <button
                key={s.key}
                className={`btn btn-sm ${filter === s.key ? "btn-primary" : "btn-secondary"}`}
                style={{ width: "auto" }}
                onClick={() => setFilter(s.key)}
                title={s.title}
                aria-pressed={filter === s.key}
                aria-label={`${s.label}${count != null ? ` (${count})` : ""}`}
              >
                {s.label}
                {s.key !== "ALL" && count != null && (
                  <span style={{
                    marginLeft: 5,
                    background: "rgba(255,255,255,0.15)",
                    borderRadius: 10,
                    padding: "0 6px",
                    fontSize: "0.68rem",
                    fontWeight: 600,
                  }}>
                    {count}
                  </span>
                )}
              </button>
            );
          })}
        </div>

        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading certificates...</span></div>
        ) : filtered.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">
              {filter === "REVOKED" ? "🚫" : filter === "INVALID" ? "⛓️" : "📜"}
            </div>
            <div className="empty-title">
              {filter === "ALL"
                ? "No certificates found"
                : `No ${filter.toLowerCase()} certificates`}
            </div>
            <p className="empty-sub">
              {filter === "ALL"
                ? "Trigger a scan on a target to discover certificates."
                : filter === "REVOKED"
                  ? "No revoked certificates detected. CertGuard checks daily."
                  : filter === "INVALID"
                    ? "No certificates with invalid chains. Chain validation runs on every scan."
                    : `No certificates with status ${filter}.`}
            </p>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Common Name</th>
                  <th>Issuer</th>
                  <th>Status</th>
                  <th>Revocation</th>
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
                    <td>
                      <Badge
                        type={certBadgeType(c.status, c.onHold)}
                        title={c.onHold ? "On hold — reversible suspension" : undefined}
                      >
                        {certStatusLabel(c.status, c.onHold)}
                      </Badge>
                    </td>
                    <td>
                      {c.revocationStatus && c.revocationStatus !== "UNCHECKED"
                        ? <Badge type={
                            c.revocationStatus === "REVOKED" ? (c.onHold ? "on-hold" : "revoked") :
                            c.revocationStatus === "GOOD"    ? "valid" : "unknown"
                          }>
                            {c.revocationStatus === "GOOD" ? "Good" : c.revocationStatus}
                          </Badge>
                        : <span style={{ color: "var(--muted)", fontSize: "0.75rem" }}>—</span>
                      }
                    </td>
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
