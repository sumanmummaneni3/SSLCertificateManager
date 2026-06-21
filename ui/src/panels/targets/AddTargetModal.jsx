import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { isRfc1918 } from "@/lib/validation.js";
import { Spinner } from "@/components/index.js";

export function AddTargetModal({ token, onClose, onAdded, toast, forOrg }) {
  // `forOrg` ({ id, name }) scopes the target to a specific organization (e.g. an MSP
  // adding to a client org). When omitted, the target is created in the caller's own
  // organization (the dashboard "Add Target" flow).
  const [host, setHost]           = useState("");
  const [port, setPort]           = useState("443");
  const [desc, setDesc]           = useState("");
  const [isPrivate, setIsPrivate] = useState(false);
  const [agentId, setAgentId]     = useState("");
  const [agents, setAgents]       = useState([]);
  const [loading, setLoading]     = useState(false);
  const [error, setError]         = useState("");

  useEffect(() => {
    api.listAgents(token).then(setAgents).catch(() => {});
  }, [token]);

  const handleAdd = async () => {
    if (!host.trim()) { setError("Host is required"); return; }
    if (isPrivate && !agentId) { setError("Select an agent for private targets"); return; }
    setError(""); setLoading(true);
    try {
      const payload = {
        host: host.trim().toLowerCase(),
        port: parseInt(port) || 443,
        isPrivate,
        agentId: isPrivate ? agentId : undefined,
        description: desc.trim() || undefined,
      };
      const target = forOrg?.id
        ? await api.createTargetForOrg(forOrg.id, payload, token)
        : await api.createTarget(payload, token);
      toast(`Target added: ${target.host}:${target.port}`, "success");
      onAdded(target);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="add-modal-title">
        <div className="modal-title" id="add-modal-title">Add Target</div>
        <p className="modal-sub">
          Enter a domain, IP address, or hostname to monitor.<br />
          Private IPs (10.x, 172.16–31.x, 192.168.x) auto-switch to Private.
        </p>

        {error && <div className="alert alert-error" role="alert">⚠ {error}</div>}

        {forOrg?.id && (
          <div className="field">
            <label>Organization</label>
            <div className="badge badge-domain" style={{ display: "inline-block" }}>{forOrg.name}</div>
          </div>
        )}

        <div className="field">
          <label htmlFor="add-host">Host *</label>
          <input id="add-host" value={host} onChange={(e) => {
              const v = e.target.value;
              setHost(v);
              if (isRfc1918(v)) setIsPrivate(true);
            }}
            onKeyDown={(e) => e.key === "Enter" && handleAdd()}
            placeholder="google.com or 192.168.1.10 or my-server" autoFocus />
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
          <div className="field">
            <label htmlFor="add-port">Port</label>
            <input id="add-port" value={port} onChange={(e) => setPort(e.target.value)}
              type="number" min="1" max="65535" placeholder="443" />
          </div>
          <div className="field">
            <label htmlFor="add-visibility">Visibility</label>
            <select id="add-visibility" value={isPrivate ? "private" : "public"}
              onChange={(e) => setIsPrivate(e.target.value === "private")}>
              <option value="public">Public</option>
              <option value="private">Private (agent)</option>
            </select>
          </div>
        </div>

        {isPrivate && (
          <div className="field">
            <label htmlFor="add-agent">Assign Agent *</label>
            <select id="add-agent" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
              <option value="">— Select agent —</option>
              {agents.filter(a => a.status === "ACTIVE").map(a => (
                <option key={a.id} value={a.id}>{a.name} ({a.currentTargetCount}/{a.maxTargets} targets)</option>
              ))}
            </select>
            {agents.filter(a => a.status === "ACTIVE").length === 0 && (
              <div style={{ fontSize: "0.72rem", color: "var(--orange)", marginTop: 4 }}>
                ⚠ No active agents. Deploy an agent first (Agents page).
              </div>
            )}
          </div>
        )}

        <div className="field">
          <label htmlFor="add-desc">Description (optional)</label>
          <input id="add-desc" value={desc} onChange={(e) => setDesc(e.target.value)}
            placeholder="e.g. Production API Gateway" />
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleAdd} disabled={loading || !host.trim()}>
            {loading ? <><Spinner /> Adding...</> : "Add Target"}
          </button>
        </div>
      </div>
    </div>
  );
}
