import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { fmtDate } from "@/lib/helpers.js";
import { Spinner } from "@/components/index.js";
import { RENEWAL_HAS_PACKAGE, renewalBadgeClass } from "./constants.js";

export function RenewalHistoryList({ certId, token }) {
  const [renewals, setRenewals]   = useState([]);
  const [loading, setLoading]     = useState(true);
  const [error, setError]         = useState("");

  useEffect(() => {
    let cancelled = false;
    api.listRenewals(certId, token)
      .then((data) => { if (!cancelled) setRenewals(Array.isArray(data) ? data : []); })
      .catch((e) => { if (!cancelled) setError(e.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [certId, token]);

  if (loading) return <div style={{ padding: "0.75rem 0", color: "var(--muted)", fontSize: "0.78rem" }}><Spinner /> Loading renewal history...</div>;
  if (error)   return <div className="alert alert-error" style={{ marginTop: "0.5rem" }}>Failed to load renewal history: {error}</div>;
  if (renewals.length === 0) return (
    <p style={{ color: "var(--muted)", fontSize: "0.78rem", marginTop: "0.5rem" }}>
      No renewal history for this certificate.
    </p>
  );

  return (
    <div style={{ marginTop: "0.5rem" }}>
      {renewals.map((r) => (
        <div key={r.id} className="renewal-history-row">
          <span className={renewalBadgeClass(r.status)}>{r.status}</span>
          <span className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>
            {fmtDate(r.createdAt)}
            {r.caProvider && r.caProvider !== "NONE" && ` · ${r.caProvider}`}
          </span>
          <span style={{ fontSize: "0.72rem", color: "var(--muted)" }}>
            {r.requestedByEmail || ""}
          </span>
          <span>
            {RENEWAL_HAS_PACKAGE.has(r.status) && (
              <a
                href={api.renewalPackageUrl(r.id)}
                download
                style={{ fontSize: "0.72rem", color: "var(--accent)", textDecoration: "none" }}
                aria-label={`Download certificate package for renewal ${r.id}`}
              >
                ↓ Download
              </a>
            )}
          </span>
        </div>
      ))}
    </div>
  );
}
