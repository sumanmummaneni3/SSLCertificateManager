import { useState } from "react";
import { api } from "@/lib/api.js";
import { AGENT_NAME_RE, validateCidrs } from "@/lib/validation.js";
import { Spinner } from "@/components/index.js";

export function AgentCreateWizard({ token, locations, onClose, onCreated, toast }) {
  const [agentName, setAgentName]     = useState("");
  const [cidrs, setCidrs]             = useState("");
  const [maxTargets, setMaxTargets]   = useState("50");
  const [locationId, setLocationId]   = useState("");
  const [loading, setLoading]         = useState(false);
  const [errors, setErrors]           = useState({});

  const validate = () => {
    const e = {};
    if (!AGENT_NAME_RE.test(agentName.trim())) {
      e.agentName = "Name must be 3–64 characters and contain only A-Za-z0-9 space _ . -";
    }
    const cidrErr = validateCidrs(cidrs);
    if (cidrErr) e.cidrs = cidrErr;
    const mt = parseInt(maxTargets);
    if (!maxTargets || isNaN(mt) || mt < 1) e.maxTargets = "Must be at least 1";
    return e;
  };

  const handleSubmit = async () => {
    const e = validate();
    if (Object.keys(e).length) { setErrors(e); return; }
    setErrors({});
    setLoading(true);
    try {
      const body = {
        agentName: agentName.trim(),
        allowedCidrs: cidrs.split(",").map((s) => s.trim()).filter(Boolean),
        maxTargets: parseInt(maxTargets),
        ...(locationId ? { locationId } : {}),
      };
      const result = await api.createAgent(body, token);
      onCreated(result);
    } catch (err) {
      // Surface ProblemDetail (title + detail already merged by api.call into err.message)
      const pd = err.problemDetail || {};
      const msg = (pd.title && pd.detail)
        ? `${pd.title} — ${pd.detail}`
        : err.message;
      setErrors({ submit: msg });
      toast("Failed to create agent: " + msg, "error");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="acw-title">
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "0.4rem" }}>
          <div className="modal-title" id="acw-title">Deploy New Agent</div>
          <button className="btn-ghost" style={{ padding: "4px 8px", fontSize: "1rem", lineHeight: 1 }}
            onClick={onClose} aria-label="Close">✕</button>
        </div>
        <p className="modal-sub">
          Configure the agent. An encrypted installer bundle will be generated and
          a one-time install key will be shown — download the bundle and store the key securely.
        </p>

        {errors.submit && (
          <div className="alert alert-error" role="alert">
            <span>⚠</span> {errors.submit}
          </div>
        )}

        <div className="field">
          <label htmlFor="acw-name">
            Agent Name <span style={{ color: "var(--red)" }}>*</span>
          </label>
          <input
            id="acw-name"
            value={agentName}
            onChange={(e) => { setAgentName(e.target.value); setErrors((v) => ({ ...v, agentName: undefined })); }}
            placeholder="e.g. office-agent-01"
            autoFocus
            aria-invalid={!!errors.agentName}
            aria-describedby={errors.agentName ? "acw-name-err" : undefined}
          />
          {errors.agentName && (
            <div id="acw-name-err" style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }} role="alert">
              {errors.agentName}
            </div>
          )}
        </div>

        <div className="field">
          <label htmlFor="acw-cidrs">
            Allowed CIDRs <span style={{ color: "var(--red)" }}>*</span>
          </label>
          <input
            id="acw-cidrs"
            value={cidrs}
            onChange={(e) => { setCidrs(e.target.value); setErrors((v) => ({ ...v, cidrs: undefined })); }}
            placeholder="192.168.1.0/24, 10.0.0.0/8"
            aria-invalid={!!errors.cidrs}
            aria-describedby={errors.cidrs ? "acw-cidrs-err" : "acw-cidrs-hint"}
          />
          {errors.cidrs ? (
            <div id="acw-cidrs-err" style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }} role="alert">
              {errors.cidrs}
            </div>
          ) : (
            <div id="acw-cidrs-hint" style={{ fontSize: "0.72rem", color: "var(--muted)", marginTop: 4 }}>
              Comma-separated IPv4 CIDRs the agent is permitted to scan.
            </div>
          )}
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
          <div className="field">
            <label htmlFor="acw-max">
              Max Targets <span style={{ color: "var(--red)" }}>*</span>
            </label>
            <input
              id="acw-max"
              type="number"
              min="1"
              value={maxTargets}
              onChange={(e) => { setMaxTargets(e.target.value); setErrors((v) => ({ ...v, maxTargets: undefined })); }}
              aria-invalid={!!errors.maxTargets}
              aria-describedby={errors.maxTargets ? "acw-max-err" : undefined}
            />
            {errors.maxTargets && (
              <div id="acw-max-err" style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }} role="alert">
                {errors.maxTargets}
              </div>
            )}
          </div>
          <div className="field">
            <label htmlFor="acw-loc">Location <span style={{ color: "var(--muted)", fontWeight: 400 }}>(optional)</span></label>
            <select id="acw-loc" value={locationId} onChange={(e) => setLocationId(e.target.value)}>
              <option value="">— None —</option>
              {(locations || []).map((l) => (
                <option key={l.id} value={l.id}>{l.name}</option>
              ))}
            </select>
          </div>
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
          <button
            className="btn btn-primary"
            onClick={handleSubmit}
            disabled={loading || !agentName.trim() || !cidrs.trim() || !maxTargets}
          >
            {loading ? <><Spinner /> Creating...</> : "Create Agent"}
          </button>
        </div>
      </div>
    </div>
  );
}
