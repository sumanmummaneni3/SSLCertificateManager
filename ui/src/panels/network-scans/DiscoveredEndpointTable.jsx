import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { fmtDate, certBadgeType, certStatusLabel } from "@/lib/helpers.js";
import { Spinner, Badge } from "@/components/index.js";

// ── Constants ─────────────────────────────────────────────────────────────────

const DEVICE_ICON = {
  ROUTER:      "⬡",
  SWITCH:      "◫",
  SERVER:      "⊞",
  WORKSTATION: "◎",
  UNKNOWN:     "?",
};

// Maps endpoint state to a Badge type + display label
const STATE_BADGE = {
  OPEN_TLS:           { type: "active",  label: "TLS"    },
  OPEN_NO_TLS:        { type: "pending", label: "Open"   },
  CLOSED_OR_FILTERED: { type: "unknown", label: "Closed" },
};

const STATE_FILTER_OPTS = [
  { value: "",             label: "All States"    },
  { value: "OPEN_TLS",    label: "Open TLS"      },
  { value: "OPEN_NO_TLS", label: "Open No-TLS"   },
];

const DEVICE_FILTER_OPTS = [
  { value: "",            label: "All Devices"  },
  { value: "ROUTER",      label: "Router"       },
  { value: "SWITCH",      label: "Switch"       },
  { value: "SERVER",      label: "Server"       },
  { value: "WORKSTATION", label: "Workstation"  },
  { value: "UNKNOWN",     label: "Unknown"      },
];

// Returns true if the ISO date string is within 30 days of now
function isExpiringSoon(isoDate) {
  if (!isoDate) return false;
  return new Date(isoDate).getTime() - Date.now() < 30 * 24 * 60 * 60 * 1000;
}

// Inline style shared by both filter <select> elements
const filterSelectStyle = {
  background:   "var(--surface2)",
  border:       "1px solid var(--border2)",
  borderRadius: "var(--radius)",
  color:        "var(--text)",
  fontFamily:   "var(--font-mono)",
  fontSize:     "0.8rem",
  padding:      "6px 12px",
  outline:      "none",
  cursor:       "pointer",
};

// ── Component ─────────────────────────────────────────────────────────────────

export function DiscoveredEndpointTable({ scanId, orgId, token }) {
  const [endpoints, setEndpoints]         = useState([]);
  const [loading, setLoading]             = useState(true);
  const [stateFilter, setStateFilter]     = useState("");
  const [deviceFilter, setDeviceFilter]   = useState("");
  const [page, setPage]                   = useState(0);
  const [totalPages, setTotalPages]       = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  // Track which rows have their banners expanded
  const [expandedBanners, setExpandedBanners] = useState({});

  useEffect(() => {
    let cancelled = false;

    // Reset to loading state for this new fetch round.
    // Initialising in the callback (rather than synchronously in the effect body)
    // avoids the react-hooks/set-state-in-effect lint rule while still showing a
    // spinner whenever any of the filter/page deps change.
    const startFetch = async () => {
      if (cancelled) return;
      setLoading(true);
      try {
        const data = await api.networkScans.listEndpoints(token, orgId, scanId, {
          state:       stateFilter  || undefined,
          deviceClass: deviceFilter || undefined,
          page,
          size: 50,
        });
        if (cancelled) return;
        setEndpoints(data?.content || []);
        setTotalPages(data?.totalPages ?? 0);
        setTotalElements(data?.totalElements ?? 0);
      } catch {
        if (!cancelled) setEndpoints([]);
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    startFetch();

    return () => {
      cancelled = true;
    };
  }, [scanId, orgId, token, stateFilter, deviceFilter, page]);

  const toggleBanners = (id) =>
    setExpandedBanners((prev) => ({ ...prev, [id]: !prev[id] }));

  const handleStateFilter = (val) => {
    setStateFilter(val);
    setPage(0);
  };

  const handleDeviceFilter = (val) => {
    setDeviceFilter(val);
    setPage(0);
  };

  return (
    <div>
      {/* ── Filters ── */}
      <div
        style={{
          display:    "flex",
          gap:        "0.75rem",
          marginBottom: "1rem",
          flexWrap:   "wrap",
          alignItems: "center",
        }}
        role="group"
        aria-label="Filter discovered endpoints"
      >
        <select
          value={stateFilter}
          onChange={(e) => handleStateFilter(e.target.value)}
          aria-label="Filter by port state"
          style={filterSelectStyle}
        >
          {STATE_FILTER_OPTS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>

        <select
          value={deviceFilter}
          onChange={(e) => handleDeviceFilter(e.target.value)}
          aria-label="Filter by device class"
          style={filterSelectStyle}
        >
          {DEVICE_FILTER_OPTS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>

        <span
          style={{
            fontSize:    "0.75rem",
            color:       "var(--muted)",
            fontFamily:  "var(--font-mono)",
          }}
          aria-live="polite"
          aria-atomic="true"
        >
          {totalElements} endpoint{totalElements !== 1 ? "s" : ""}
        </span>
      </div>

      {/* ── Content ── */}
      {loading ? (
        <div className="loading-center">
          <Spinner lg />
          <span>Loading endpoints…</span>
        </div>
      ) : endpoints.length === 0 ? (
        <div className="empty" style={{ padding: "2rem 0" }}>
          <div className="empty-title">No endpoints match your filters</div>
          <p className="empty-sub">Try adjusting the state or device class filter above.</p>
        </div>
      ) : (
        <>
          <div className="table-wrap" style={{ marginBottom: "1rem" }}>
            <table>
              <thead>
                <tr>
                  <th>IP</th>
                  <th>Port</th>
                  <th>State</th>
                  <th>Device</th>
                  <th>TLS Subject</th>
                  <th>Expires</th>
                  <th>Cert Status</th>
                  <th>Banners</th>
                </tr>
              </thead>
              <tbody>
                {endpoints.map((ep) => {
                  const badge       = STATE_BADGE[ep.state] || { type: "unknown", label: ep.state };
                  const expireSoon  = ep.state === "OPEN_TLS" && isExpiringSoon(ep.tlsNotAfter);
                  const bannerKeys  = ep.banners ? Object.keys(ep.banners) : [];
                  const isExpanded  = !!expandedBanners[ep.id];

                  return (
                    <tr key={ep.id}>
                      <td
                        className="mono"
                        style={{ fontSize: "0.78rem" }}
                      >
                        {ep.ip}
                      </td>
                      <td className="mono" style={{ fontSize: "0.78rem" }}>
                        {ep.port}
                      </td>
                      <td>
                        <Badge type={badge.type}>{badge.label}</Badge>
                      </td>
                      <td style={{ whiteSpace: "nowrap" }}>
                        <span aria-hidden="true" style={{ marginRight: 4 }}>
                          {DEVICE_ICON[ep.deviceClass] || "?"}
                        </span>
                        <span
                          style={{
                            fontSize:  "0.75rem",
                            color:     "var(--muted)",
                          }}
                        >
                          {ep.deviceClass || "UNKNOWN"}
                        </span>
                      </td>
                      <td
                        className="mono"
                        style={{ fontSize: "0.75rem" }}
                      >
                        {ep.tlsSubjectCn || "—"}
                      </td>
                      <td
                        className="mono"
                        style={{
                          fontSize: "0.75rem",
                          color:    expireSoon ? "var(--red)" : "var(--muted)",
                        }}
                      >
                        {ep.tlsNotAfter ? fmtDate(ep.tlsNotAfter) : "—"}
                      </td>
                      <td>
                        {ep.tlsCertStatus ? (
                          <Badge type={certBadgeType(ep.tlsCertStatus, false)}>
                            {certStatusLabel(ep.tlsCertStatus, false)}
                          </Badge>
                        ) : (
                          "—"
                        )}
                      </td>
                      <td>
                        {bannerKeys.length > 0 && (
                          <>
                            <button
                              className="scan-btn"
                              onClick={() => toggleBanners(ep.id)}
                              aria-label={
                                isExpanded
                                  ? `Hide banners for ${ep.ip}:${ep.port}`
                                  : `Show banners for ${ep.ip}:${ep.port}`
                              }
                              aria-expanded={isExpanded}
                            >
                              {isExpanded ? "▲ Hide" : "▼ Show"}
                            </button>
                            {isExpanded && (
                              <pre
                                style={{
                                  marginTop:   4,
                                  background:  "var(--surface2)",
                                  border:      "1px solid var(--border2)",
                                  borderRadius: "var(--radius)",
                                  padding:     "6px 10px",
                                  fontSize:    "0.72rem",
                                  fontFamily:  "var(--font-mono)",
                                  color:       "var(--accent)",
                                  lineHeight:  1.7,
                                  whiteSpace:  "pre-wrap",
                                  wordBreak:   "break-all",
                                  margin:      "4px 0 0",
                                }}
                                aria-label={`Banner data for ${ep.ip}:${ep.port}`}
                              >
                                {bannerKeys
                                  .map((k) => `${k}: ${ep.banners[k]}`)
                                  .join("\n")}
                              </pre>
                            )}
                          </>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {/* ── Pagination ── */}
          {totalPages > 1 && (
            <div className="pagination">
              <button
                className="btn btn-secondary btn-sm"
                disabled={page === 0}
                onClick={() => setPage((p) => p - 1)}
                aria-label="Previous page"
              >
                Previous
              </button>
              <span className="pagination-info" aria-live="polite">
                Page {page + 1} of {totalPages}
              </span>
              <button
                className="btn btn-secondary btn-sm"
                disabled={page >= totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
                aria-label="Next page"
              >
                Next
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
