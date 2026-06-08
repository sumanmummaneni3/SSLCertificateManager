import { useState, useEffect, useCallback } from "react";
import { api } from "@/lib/api.js";
import { statusColor, hostTypeColor, fmtDate, fmtRelative } from "@/lib/helpers.js";
import { Spinner, Badge } from "@/components/index.js";
import { providerLabel, providerColor } from "@/panels/locations/providers.js";

// eslint-disable-next-line no-unused-vars
export function PlatformOrgDetailView({ token, toast, actingAsOrgId, actingAsOrgName, onExit, me }) {
  const [detailTab, setDetailTab] = useState("targets");
  const [targets, setTargets]     = useState([]);
  const [targetsLoading, setTargetsLoading] = useState(false);
  const [agents, setAgents]       = useState([]);
  const [agentsLoading, setAgentsLoading] = useState(false);
  const [members, setMembers]     = useState([]);
  const [membersLoading, setMembersLoading] = useState(false);
  const [locations, setLocations] = useState([]);
  const [locationsLoading, setLocationsLoading] = useState(false);
  const [scanning, setScanning]   = useState({});

  const loadTargets = useCallback(async () => {
    const opts = { actingAsOrgId, reason: "Platform admin inspection" };
    setTargetsLoading(true);
    try { const r = await api.getTargets(token, opts); setTargets(r?.content || []); }
    catch (e) { toast("Failed to load targets: " + e.message, "error"); }
    finally { setTargetsLoading(false); }
  }, [token, actingAsOrgId, toast]);

  const loadAgents = useCallback(async () => {
    const opts = { actingAsOrgId, reason: "Platform admin inspection" };
    setAgentsLoading(true);
    try { setAgents(await api.listAgents(token, opts)); }
    catch (e) { toast("Failed to load agents: " + e.message, "error"); }
    finally { setAgentsLoading(false); }
  }, [token, actingAsOrgId, toast]);

  const loadMembers = useCallback(async () => {
    const opts = { actingAsOrgId, reason: "Platform admin inspection" };
    setMembersLoading(true);
    try { setMembers(await api.listMembers(token, opts)); }
    catch (e) { toast("Failed to load members: " + e.message, "error"); }
    finally { setMembersLoading(false); }
  }, [token, actingAsOrgId, toast]);

  const loadLocations = useCallback(async () => {
    const opts = { actingAsOrgId, reason: "Platform admin inspection" };
    setLocationsLoading(true);
    try { setLocations(await api.listLocations(token, opts)); }
    catch (e) { toast("Failed to load locations: " + e.message, "error"); }
    finally { setLocationsLoading(false); }
  }, [token, actingAsOrgId, toast]);

  useEffect(() => { if (detailTab === "targets")   loadTargets();   }, [detailTab, loadTargets]);
  useEffect(() => { if (detailTab === "agents")    loadAgents();    }, [detailTab, loadAgents]);
  useEffect(() => { if (detailTab === "members")   loadMembers();   }, [detailTab, loadMembers]);
  useEffect(() => { if (detailTab === "locations") loadLocations(); }, [detailTab, loadLocations]);

  const triggerScan = async (target) => {
    const opts = { actingAsOrgId, reason: "Platform admin inspection" };
    setScanning((s) => ({ ...s, [target.id]: true }));
    try {
      await api.scanTarget(target.id, token, opts);
      toast(`Scan triggered for ${target.host}`, "info");
      setTimeout(() => { loadTargets(); setScanning((s) => ({ ...s, [target.id]: false })); }, 8000);
    } catch (e) {
      toast("Scan failed: " + e.message, "error");
      setScanning((s) => ({ ...s, [target.id]: false }));
    }
  };

  const agentStatusBadgeType = (s) =>
    ({ ACTIVE: "active", PENDING: "pending", OFFLINE: "offline", REVOKED: "revoked" }[s] || "pending");

  const inviteStatusBadgeType = (s) =>
    ({ ACCEPTED: "active", PENDING: "pending", REVOKED: "revoked" }[s] || "unknown");

  return (
    <>
      <div className="page-header">
        <div>
          <button
            className="btn-ghost"
            style={{ padding: 0, fontSize: "0.78rem", marginBottom: 6, display: "flex", alignItems: "center", gap: 4 }}
            onClick={onExit}
            aria-label="Back to All Orgs"
          >
            &#8592; Back to All Orgs
          </button>
          <div className="page-title">{actingAsOrgName || actingAsOrgId}</div>
          <div className="page-sub">Inspecting organisation — all actions are logged</div>
        </div>
      </div>
      <div className="page-content">
        <div className="admin-tabs" role="tablist" aria-label="Organisation detail tabs">
          {[
            { id: "targets",   label: "Targets"   },
            { id: "agents",    label: "Agents"    },
            { id: "members",   label: "Members"   },
            { id: "locations", label: "Locations" },
          ].map(({ id, label }) => (
            <button
              key={id}
              className={`admin-tab ${detailTab === id ? "active" : ""}`}
              role="tab"
              aria-selected={detailTab === id}
              onClick={() => setDetailTab(id)}
            >
              {label}
            </button>
          ))}
        </div>

        {detailTab === "targets" && (
          targetsLoading ? (
            <div className="loading-center"><Spinner lg /><span>Loading targets...</span></div>
          ) : targets.length === 0 ? (
            <div className="empty">
              <div className="empty-icon">🎯</div>
              <div className="empty-title">No targets</div>
              <p className="empty-sub">This organisation has no monitored targets.</p>
            </div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Host</th>
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
                        <td>
                          <div className="host-cell">{t.host}</div>
                          {t.description && <div className="mono">{t.description}</div>}
                        </td>
                        <td><Badge type={hostTypeColor(t.hostType)}>{t.hostType || "—"}</Badge></td>
                        <td>
                          <Badge type={t.isPrivate ? "private" : "public"}>
                            {t.isPrivate ? "Private" : "Public"}
                          </Badge>
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
                          <button
                            className={`scan-btn ${scanning[t.id] ? "scanning" : ""}`}
                            onClick={() => triggerScan(t)}
                            disabled={scanning[t.id]}
                            title="Trigger scan"
                          >
                            {scanning[t.id] ? <Spinner /> : "⟳"}
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )
        )}

        {detailTab === "agents" && (
          agentsLoading ? (
            <div className="loading-center"><Spinner lg /><span>Loading agents...</span></div>
          ) : agents.length === 0 ? (
            <div className="empty">
              <div className="empty-icon">⬡</div>
              <div className="empty-title">No agents</div>
              <p className="empty-sub">This organisation has no registered agents.</p>
            </div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Status</th>
                    <th>Targets</th>
                    <th>Last Seen</th>
                    <th>Registered</th>
                  </tr>
                </thead>
                <tbody>
                  {agents.map((a) => (
                    <tr key={a.id}>
                      <td className="host-cell">{a.name}</td>
                      <td><Badge type={agentStatusBadgeType(a.status)}>{a.status}</Badge></td>
                      <td className="mono">{a.currentTargetCount ?? 0} / {a.maxTargets}</td>
                      <td className="mono" style={{ color: "var(--muted)", fontSize: "0.75rem" }}>{fmtRelative(a.lastSeenAt)}</td>
                      <td className="mono" style={{ color: "var(--muted)", fontSize: "0.75rem" }}>{fmtDate(a.registeredAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}

        {detailTab === "members" && (
          membersLoading ? (
            <div className="loading-center"><Spinner lg /><span>Loading members...</span></div>
          ) : members.length === 0 ? (
            <div className="empty">
              <div className="empty-icon">◎</div>
              <div className="empty-title">No members</div>
              <p className="empty-sub">This organisation has no team members.</p>
            </div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Member</th>
                    <th>Role</th>
                    <th>Status</th>
                    <th>Joined</th>
                  </tr>
                </thead>
                <tbody>
                  {members.map((m) => (
                    <tr key={m.id}>
                      <td>
                        <div style={{ fontWeight: 500 }}>{m.name || m.email}</div>
                        {m.name && <div className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>{m.email}</div>}
                      </td>
                      <td><Badge type={m.role === "ADMIN" ? "active" : "pending"}>{m.role}</Badge></td>
                      <td><Badge type={inviteStatusBadgeType(m.inviteStatus)}>{m.inviteStatus}</Badge></td>
                      <td className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>{fmtDate(m.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}

        {detailTab === "locations" && (
          locationsLoading ? (
            <div className="loading-center"><Spinner lg /><span>Loading locations...</span></div>
          ) : locations.length === 0 ? (
            <div className="empty">
              <div className="empty-icon">📍</div>
              <div className="empty-title">No locations</div>
              <p className="empty-sub">This organisation has no configured locations.</p>
            </div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Provider</th>
                    <th>Geo Region</th>
                    <th>Cloud Region</th>
                    <th>Targets</th>
                  </tr>
                </thead>
                <tbody>
                  {locations.map((loc) => (
                    <tr key={loc.id}>
                      <td className="host-cell">{loc.name}</td>
                      <td><Badge type={providerColor(loc.provider)}>{providerLabel(loc.provider)}</Badge></td>
                      <td className="mono">{loc.geoRegion || "—"}</td>
                      <td className="mono">{loc.cloudRegion || "—"}</td>
                      <td className="mono">{loc.targetCount ?? 0}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}
      </div>
    </>
  );
}
