import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function EditTargetModal({ token, target, onClose, onSaved, toast }) {
  const [host, setHost]           = useState(target.host);
  const [port, setPort]           = useState(String(target.port));
  const [desc, setDesc]           = useState(target.description || "");
  const [isPrivate, setIsPrivate] = useState(!!target.isPrivate);
  const [enabled, setEnabled]     = useState(target.enabled !== false);
  const [agentId, setAgentId]     = useState(target.agentId || "");
  const [agents, setAgents]       = useState([]);
  const [loading, setLoading]     = useState(false);
  const [error, setError]         = useState("");

  useEffect(() => {
    api.listAgents(token).then(setAgents).catch(() => {});
  }, [token]);

  const handleSave = async () => {
    if (!host.trim()) { setError("Host is required"); return; }
    if (isPrivate && !agentId) { setError("Select an agent for private targets"); return; }
    setError(""); setLoading(true);
    try {
      const updated = await api.updateTarget(target.id, {
        host: host.trim().toLowerCase(),
        port: parseInt(port) || 443,
        isPrivate,
        enabled,
        description: desc.trim() || null,
        agentId: agentId || null,
      }, token);
      toast(`Target updated: ${updated.host}:${updated.port}`, "success");
      onSaved();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="edit-modal-title">
        <div className="modal-title" id="edit-modal-title">Edit Target</div>
        <p className="modal-sub">Update the monitored endpoint details.</p>

        {error && <div className="alert alert-error" role="alert">⚠ {error}</div>}

        <div className="field">
          <label htmlFor="edit-host">Host *</label>
          <input id="edit-host" value={host} onChange={(e) => setHost(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleSave()}
            placeholder="google.com or 192.168.1.10" autoFocus />
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: "0.75rem" }}>
          <div className="field">
            <label htmlFor="edit-port">Port</label>
            <input id="edit-port" value={port} onChange={(e) => setPort(e.target.value)}
              type="number" min="1" max="65535" placeholder="443" />
          </div>
          <div className="field">
            <label htmlFor="edit-visibility">Visibility</label>
            <select id="edit-visibility" value={isPrivate ? "private" : "public"}
              onChange={(e) => setIsPrivate(e.target.value === "private")}>
              <option value="public">Public</option>
              <option value="private">Private (agent)</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="edit-monitoring">Monitoring</label>
            <select id="edit-monitoring" value={enabled ? "on" : "off"}
              onChange={(e) => setEnabled(e.target.value === "on")}>
              <option value="on">Enabled</option>
              <option value="off">Disabled</option>
            </select>
          </div>
        </div>

        {isPrivate && (
          <div className="field">
            <label htmlFor="edit-agent">Assign Agent *</label>
            <select id="edit-agent" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
              <option value="">— Select agent —</option>
              {agents.filter(a => a.status === "ACTIVE").map(a => (
                <option key={a.id} value={a.id}>{a.name} ({a.currentTargetCount}/{a.maxTargets} targets)</option>
              ))}
            </select>
          </div>
        )}

        <div className="field">
          <label htmlFor="edit-desc">Description (optional)</label>
          <input id="edit-desc" value={desc} onChange={(e) => setDesc(e.target.value)}
            placeholder="e.g. Production API Gateway" />
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSave} disabled={loading || !host.trim()}>
            {loading ? <><Spinner /> Saving...</> : "Save Changes"}
          </button>
        </div>
      </div>
    </div>
  );
}
