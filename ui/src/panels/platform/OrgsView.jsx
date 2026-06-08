import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { Spinner, Badge } from "@/components/index.js";

export function PlatformOrgsView({ token, toast, onManageOrg }) {
  const [orgs, setOrgs]         = useState([]);
  const [loading, setLoading]   = useState(true);
  const [apiUnavailable, setApiUnavailable] = useState(false);
  const [tab, setTab]           = useState("all"); // all | msps | single | pending
  const [search, setSearch]     = useState("");
  const [activatingId, setActivatingId] = useState(null);

  useEffect(() => {
    let cancelled = false;
    const fetchOrgs = async () => {
      try {
        const data = await api.admin.getOrgTree(token);
        if (!cancelled) {
          setOrgs(Array.isArray(data) ? data : (data?.content || []));
        }
      } catch (e) {
        if (!cancelled) {
          if (e.status === 404 || e.message?.includes("404")) {
            setApiUnavailable(true);
          } else {
            toast("Failed to load organisations: " + e.message, "error");
          }
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    fetchOrgs();
    return () => { cancelled = true; };
  }, [token, toast]);

  if (loading) {
    return (
      <div className="loading-center" style={{ minHeight: "60vh" }}>
        <Spinner lg /><span>Loading organisations...</span>
      </div>
    );
  }

  if (apiUnavailable) {
    return (
      <>
        <div className="page-header">
          <div>
            <div className="page-title">All Organisations</div>
            <div className="page-sub">Platform-wide organisation management</div>
          </div>
        </div>
        <div className="page-content">
          <div className="admin-api-unavailable">
            <div style={{ fontSize: "2rem", marginBottom: "1rem", opacity: 0.4 }}>◫</div>
            <div style={{ fontFamily: "var(--font-head)", fontSize: "1rem", color: "var(--text)", marginBottom: "0.5rem" }}>
              Admin API not yet available
            </div>
            <div style={{ fontSize: "0.82rem" }}>
              The admin endpoints are not deployed in this environment. They will appear automatically once deployed.
            </div>
          </div>
        </div>
      </>
    );
  }

  const handleActivateMsp = async (orgId, orgName) => {
    setActivatingId(orgId);
    try {
      await api.admin.promoteMsp(token, orgId);
      toast(`MSP activated for ${orgName}`, "success");
      const data = await api.admin.getOrgTree(token);
      setOrgs(Array.isArray(data) ? data : (data?.content || []));
    } catch (e) {
      toast("Activate MSP failed: " + e.message, "error");
    } finally {
      setActivatingId(null);
    }
  };

  // Flatten tree for display — each org may have children[] array for MSP clients
  const flatAll = [];
  const flattenOrg = (o, depth = 0) => {
    flatAll.push({ ...o, _depth: depth });
    if (Array.isArray(o.children)) o.children.forEach((c) => flattenOrg(c, depth + 1));
  };
  orgs.forEach((o) => flattenOrg(o));

  const q = search.trim().toLowerCase();
  const filtered = flatAll.filter((o) => {
    if (q && !o.name?.toLowerCase().includes(q)) return false;
    if (tab === "msps")    return o.orgType === "MSP";
    if (tab === "single")  return o.orgType !== "MSP";
    if (tab === "pending") return o.subscriptionStatus === "PENDING_ACTIVATION";
    return true;
  });

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">All Organisations</div>
          <div className="page-sub">Platform-wide organisation management ({flatAll.length} total)</div>
        </div>
        <div className="search-bar" style={{ minWidth: 200 }}>
          <span aria-hidden="true" style={{ color: "var(--muted)", fontSize: "0.8rem" }}>&#128269;</span>
          <input
            type="search"
            placeholder="Search orgs..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            aria-label="Search organisations"
          />
        </div>
      </div>
      <div className="page-content">
        <div className="admin-tabs" role="tablist" aria-label="Organisation filter">
          {[
            { id: "all",     label: "All" },
            { id: "msps",    label: "MSPs" },
            { id: "single",  label: "Single" },
            { id: "pending", label: "Pending" },
          ].map(({ id, label }) => (
            <button
              key={id}
              className={`admin-tab ${tab === id ? "active" : ""}`}
              role="tab"
              aria-selected={tab === id}
              onClick={() => setTab(id)}
            >
              {label}
            </button>
          ))}
        </div>

        {filtered.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">◫</div>
            <div className="empty-title">No organisations found</div>
            <p className="empty-sub">
              {q ? `No results for "${search}".` : "No organisations match the current filter."}
            </p>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Type</th>
                  <th>Status</th>
                  <th>Parent</th>
                  <th>Targets</th>
                  <th>Agents</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((o) => (
                  <tr key={o.id}>
                    <td>
                      <div style={{ display: "flex", alignItems: "center", gap: 6, paddingLeft: o._depth * 16 }}>
                        {o._depth > 0 && (
                          <span aria-hidden="true" style={{ color: "var(--border2)", fontSize: "0.8rem" }}>&#x2514;</span>
                        )}
                        <span className="host-cell">{o.name}</span>
                      </div>
                      {o.domain && <div className="mono" style={{ fontSize: "0.7rem", color: "var(--muted)", paddingLeft: o._depth * 16 + (o._depth > 0 ? 18 : 0) }}>{o.domain}</div>}
                    </td>
                    <td>
                      <Badge type={o.orgType === "MSP" ? "active" : "pending"}>{o.orgType || "SINGLE"}</Badge>
                    </td>
                    <td>
                      {o.subscriptionStatus ? (
                        <Badge type={o.subscriptionStatus === "ACTIVE" ? "active" : o.subscriptionStatus === "SUSPENDED" ? "revoked" : "pending"}>
                          {o.subscriptionStatus}
                        </Badge>
                      ) : <span className="text-muted">—</span>}
                    </td>
                    <td className="mono" style={{ color: "var(--muted)", fontSize: "0.78rem" }}>
                      {o.parentName || "—"}
                    </td>
                    <td className="mono">{o.targetCount ?? 0}</td>
                    <td className="mono">{o.agentCount ?? 0}</td>
                    <td>
                      <div className="row-actions">
                        <button
                          className="btn btn-secondary btn-sm"
                          onClick={() => onManageOrg(o.id, o.name)}
                          aria-label={`Manage organisation ${o.name}`}
                        >
                          Manage
                        </button>
                        {o.subscriptionStatus === "PENDING_ACTIVATION" && (
                          <button
                            className="btn btn-primary btn-sm"
                            onClick={() => handleActivateMsp(o.id, o.name)}
                            disabled={activatingId === o.id}
                            aria-label={`Activate MSP for ${o.name}`}
                          >
                            {activatingId === o.id ? <Spinner /> : "Activate MSP"}
                          </button>
                        )}
                      </div>
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
