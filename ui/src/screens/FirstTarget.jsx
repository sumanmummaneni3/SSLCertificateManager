import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function FirstTarget({ token, onDone, toast }) {
  const [host, setHost]           = useState("");
  const [port, setPort]           = useState("443");
  const [desc, setDesc]           = useState("");
  const [loading, setLoading]     = useState(false);
  const [scanning, setScanning]   = useState(false);
  const [error, setError]         = useState("");
  const [addedTarget, setAddedTarget] = useState(null);

  const handleAdd = async () => {
    if (!host.trim()) { setError("Host is required"); return; }
    setError(""); setLoading(true);
    try {
      const target = await api.createTarget({
        host: host.trim().toLowerCase(),
        port: parseInt(port) || 443,
        description: desc.trim() || undefined,
      }, token);
      setAddedTarget(target);
      toast(`Target added: ${target.host}`, "success");

      // Auto-trigger scan if public
      if (!target.isPrivate) {
        setScanning(true);
        try {
          await api.scanTarget(target.id, token);
          toast("Certificate scan triggered!", "info");
        } catch (e) {
          toast("Scan failed — " + e.message, "error");
        } finally {
          setScanning(false);
        }
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  if (addedTarget) {
    return (
      <div className="launch">
        <div className="launch-logo">
          <div className="logo-icon">🎯</div>
          <div className="logo-text">OOPSSSL</div>
        </div>
        <div className="launch-card">
          <div className="steps">
            <div className="step-item done"><span className="step-num">✓</span>Signed In</div>
            <div className="step-item done"><span className="step-num">✓</span>Your Org</div>
            <div className="step-item active"><span className="step-num">3</span>Add Targets</div>
          </div>
          <div className="launch-title">Target added! ✓</div>
          <div className="cert-detail" style={{ marginBottom: "1.5rem" }}>
            <div className="cert-row"><span className="key">Host</span><span className="val">{addedTarget.host}</span></div>
            <div className="cert-row"><span className="key">Port</span><span className="val">{addedTarget.port}</span></div>
            <div className="cert-row"><span className="key">Type</span><span className="val">{addedTarget.hostType}</span></div>
            <div className="cert-row">
              <span className="key">Visibility</span>
              <span className="val">{addedTarget.isPrivate ? "🔒 Private" : "🌐 Public"}</span>
            </div>
          </div>
          {scanning && (
            <div className="alert alert-info"><Spinner /> Scanning certificate...</div>
          )}
          <button className="btn btn-primary" onClick={onDone} disabled={scanning}>
            {scanning ? "Scanning..." : "→ Go to Dashboard"}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="launch">
      <div className="launch-logo">
        <div className="logo-icon">🎯</div>
        <div className="logo-text">OOPSSSL</div>
      </div>
      <div className="launch-card">
        <div className="steps">
          <div className="step-item done"><span className="step-num">✓</span>Signed In</div>
          <div className="step-item done"><span className="step-num">✓</span>Your Org</div>
          <div className="step-item active"><span className="step-num">3</span>Add Targets</div>
        </div>

        <div className="launch-title">Add your first target</div>
        <p className="launch-sub">
          Enter a domain, IP or hostname to monitor. We&apos;ll scan the certificate immediately for public targets.
        </p>

        {error && <div className="alert alert-error">⚠ {error}</div>}

        <div className="field">
          <label htmlFor="first-host">Host *</label>
          <input id="first-host" value={host} onChange={(e) => setHost(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleAdd()}
            placeholder="google.com or 1.1.1.1 or my-server" autoFocus />
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
          <div className="field">
            <label htmlFor="first-port">Port</label>
            <input id="first-port" value={port} onChange={(e) => setPort(e.target.value)}
              type="number" min="1" max="65535" />
          </div>
          <div className="field">
            <label htmlFor="first-desc">Description</label>
            <input id="first-desc" value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="optional" />
          </div>
        </div>

        <button className="btn btn-primary" onClick={handleAdd} disabled={loading || !host.trim()}>
          {loading ? <><Spinner /> Adding...</> : "→ Add & Scan"}
        </button>

        <div style={{ textAlign: "center", marginTop: "1rem" }}>
          <button className="btn btn-ghost" onClick={onDone}>Skip for now →</button>
        </div>
      </div>
    </div>
  );
}
