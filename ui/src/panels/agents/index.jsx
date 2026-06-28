import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { fmtDate, fmtRelative } from "@/lib/helpers.js";
import { Spinner, Badge } from "@/components/index.js";
import { AgentCreateWizard } from "./AgentCreateWizard.jsx";
import { AgentInstallKeyModal } from "./AgentInstallKeyModal.jsx";

// Local helper — renamed from the module-level statusColor to avoid shadowing
function agentStatusColor(s) {
  return { ACTIVE: "active", PENDING: "pending", OFFLINE: "offline", REVOKED: "revoked", EXPIRED: "revoked" }[s] || "pending";
}

export function AgentsView({ agents, loading, token, onRefresh, toast, me }) {
  const [showWizard, setShowWizard]     = useState(false);
  const [installResult, setInstallResult] = useState(null);
  const [locations, setLocations]       = useState([]);

  // Load locations for the wizard dropdown
  useEffect(() => {
    api.listLocations(token).then(setLocations).catch(() => {});
  }, [token]);

  // Poll every 10 s while any agent is PENDING or OFFLINE
  useEffect(() => {
    const needsPoll = agents.some((a) => a.status === "PENDING" || a.status === "OFFLINE");
    if (!needsPoll) return;
    const id = setInterval(onRefresh, 10000);
    return () => clearInterval(id);
  }, [agents, onRefresh]);

  const handleRevoke = async (id) => {
    try {
      await api.revokeAgent(id, token);
      toast("Agent revoked", "success");
      onRefresh();
    } catch (e) {
      toast("Revoke failed: " + e.message, "error");
    }
  };

  const handleCreated = (result) => {
    setShowWizard(false);
    setInstallResult(result);
    onRefresh();
  };

  const handleInstallDone = () => {
    setInstallResult(null);
  };

  const canWrite = me == null || me?.permissions?.canWriteAgents;
  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Agents</div>
          <div className="page-sub">On-premise agents for private network scanning</div>
        </div>
        <div style={{ display: "flex", gap: "0.5rem" }}>
          <button className="btn btn-secondary btn-sm" onClick={onRefresh}>↻ Refresh</button>
          {canWrite && (
            <button className="btn btn-primary btn-sm" onClick={() => setShowWizard(true)}>+ Deploy New Agent</button>
          )}
        </div>
      </div>
      <div className="page-content">

        <div className="alert alert-info" style={{ marginBottom: "1.5rem" }}>
          <span>ℹ</span>
          <div>
            Agents run inside your private network and scan hosts not reachable from the internet.
            Communication uses TLS 1.3 + AES-256-GCM with HMAC-SHA256 payload signing.
          </div>
        </div>

        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading agents...</span></div>
        ) : agents.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">⬡</div>
            <div className="empty-title">No agents registered</div>
            <p className="empty-sub">
              Deploy an agent on your private network to start scanning internal hosts.
              Click &quot;+ Deploy New Agent&quot; to create an encrypted installer bundle.
            </p>
            {canWrite && (
              <button className="btn btn-primary btn-sm" onClick={() => setShowWizard(true)}
                style={{ margin: "0 auto" }}>+ Deploy First Agent</button>
            )}
          </div>
        ) : (
          <div className="table-wrap" style={{ marginBottom: "2rem" }}>
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Status</th>
                  <th>Targets</th>
                  <th>Discovered Subnets</th>
                  <th>Last Seen</th>
                  <th>Registered</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {agents.map((a) => (
                  <tr key={a.id}>
                    <td className="host-cell">
                      {a.name}
                      {a.locationName && (
                        <div className="mono" style={{ fontSize: "0.7rem", color: "var(--muted)" }}>{a.locationName}</div>
                      )}
                    </td>
                    <td><Badge type={agentStatusColor(a.status)}>{a.status}</Badge></td>
                    <td className="mono">{a.currentTargetCount ?? 0} / {a.maxTargets}</td>
                    <td>
                      {(a.discoveredSubnets || []).length > 0 ? (
                        <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                          {(a.discoveredSubnets || []).map((c) => (
                            <span key={c} className="badge badge-domain" style={{ fontSize: "0.68rem" }}>{c}</span>
                          ))}
                        </div>
                      ) : (
                        <span style={{ color: "var(--muted)", fontSize: "0.75rem" }}>Waiting for heartbeat…</span>
                      )}
                    </td>
                    <td className="mono" style={{ color: "var(--muted)", fontSize: "0.75rem" }}>
                      {fmtRelative(a.lastSeenAt)}
                    </td>
                    <td className="mono" style={{ color: "var(--muted)", fontSize: "0.75rem" }}>
                      {fmtDate(a.registeredAt)}
                    </td>
                    <td>
                      {canWrite && (a.status === "ACTIVE" || a.status === "PENDING") && (
                        <button
                          className="scan-btn"
                          style={{ color: "var(--red)", borderColor: "rgba(255,82,82,0.3)" }}
                          onClick={() => handleRevoke(a.id)}
                          aria-label={`Revoke agent ${a.name}`}
                        >
                          Revoke
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showWizard && (
        <AgentCreateWizard
          token={token}
          locations={locations}
          onClose={() => setShowWizard(false)}
          onCreated={handleCreated}
          toast={toast}
        />
      )}

      {installResult && (
        <AgentInstallKeyModal
          result={installResult}
          onClose={handleInstallDone}
          toast={toast}
        />
      )}
    </>
  );
}
