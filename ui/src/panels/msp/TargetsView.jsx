import { useState, useEffect, useCallback } from "react";
import { api } from "@/lib/api.js";
import { fmtDate } from "@/lib/helpers.js";
import { Spinner, Badge } from "@/components/index.js";

export function MspTargetsView({ token, me }) {
  const [targets, setTargets]   = useState([]);
  const [loading, setLoading]   = useState(true);
  const [page, setPage]         = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const upgradePending = me?.permissions?.mspUpgradePending === true;

  const load = useCallback(async (p) => {
    if (upgradePending) return;
    setLoading(true);
    try {
      const data = await api.msp.getTargets(token, p, 20);
      setTargets(data?.content || []);
      setTotalPages(data?.totalPages ?? 0);
    } catch { /* silently ignore — no toast prop */ }
    finally { setLoading(false); }
  }, [token, upgradePending]);

  useEffect(() => { load(page); }, [load, page]);

  const handlePrev = () => { if (page > 0) setPage((p) => p - 1); };
  const handleNext = () => { if (page < totalPages - 1) setPage((p) => p + 1); };

  if (upgradePending) {
    return (
      <>
        <div className="page-header"><div className="page-title">All Targets</div></div>
        <div className="empty" style={{ padding: "4rem 2rem" }}>
          <div className="empty-icon" aria-hidden="true">⬡</div>
          <div className="empty-title">MSP Upgrade Pending</div>
          <p className="empty-sub">Your MSP upgrade request is under review by our team. You&apos;ll receive an email once it&apos;s approved and MSP features are unlocked.</p>
        </div>
      </>
    );
  }

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">All Targets</div>
          <div className="page-sub">Cross-organisation target view — read only</div>
        </div>
        <button className="btn btn-secondary btn-sm" onClick={() => load(page)}>↻ Refresh</button>
      </div>
      <div className="page-content">
        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading targets...</span></div>
        ) : targets.length === 0 ? (
          <div className="empty">
            <div className="empty-icon" aria-hidden="true">⊕</div>
            <div className="empty-title">No targets found</div>
            <p className="empty-sub">Targets added to client organisations will appear here.</p>
          </div>
        ) : (
          <>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Org</th>
                    <th>Host</th>
                    <th>Port</th>
                    <th>Type</th>
                    <th>Status</th>
                    <th>Last Scanned</th>
                  </tr>
                </thead>
                <tbody>
                  {targets.map((t) => (
                    <tr key={t.id}>
                      <td>
                        <span className="badge badge-domain" style={{ fontSize: "0.72rem" }}>{t.orgName || "—"}</span>
                      </td>
                      <td className="host-cell">{t.host}</td>
                      <td className="mono">{t.port}</td>
                      <td>
                        <Badge type={t.isPrivate ? "private" : "public"}>
                          {t.isPrivate ? "Private" : "Public"}
                        </Badge>
                      </td>
                      <td>
                        <Badge type={t.enabled ? "active" : "unknown"}>
                          {t.enabled ? "enabled" : "disabled"}
                        </Badge>
                      </td>
                      <td className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>
                        {t.lastScannedAt ? fmtDate(t.lastScannedAt) : "Never"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {totalPages > 1 && (
              <div className="pagination">
                <button className="btn btn-secondary btn-sm" onClick={handlePrev} disabled={page === 0}>
                  ← Prev
                </button>
                <span className="pagination-info">Page {page + 1} of {totalPages}</span>
                <button className="btn btn-secondary btn-sm" onClick={handleNext} disabled={page >= totalPages - 1}>
                  Next →
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </>
  );
}
