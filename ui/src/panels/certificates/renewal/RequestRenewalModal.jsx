import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function RequestRenewalModal({ certId, token, onClose, onRequested, toast }) {
  const [installPath, setInstallPath] = useState("");
  const [caProvider, setCaProvider]   = useState("");
  const [providers, setProviders]     = useState([]);
  const [loading, setLoading]         = useState(false);
  const [error, setError]             = useState("");

  useEffect(() => {
    api.listRenewalProviders(token)
      .then(list => setProviders(list || []))
      .catch(() => setProviders([]));
  }, [token]);

  const handleSubmit = async () => {
    setError(""); setLoading(true);
    try {
      const body = {
        targetInstallPath: installPath.trim() || undefined,
        caProvider: caProvider || undefined,
      };
      const renewal = await api.requestRenewal(certId, body, token);
      toast("Renewal requested — agent will generate CSR shortly", "success");
      onRequested(renewal);
    } catch (e) {
      if (e.status === 409) {
        setError("A renewal is already in progress for this certificate.");
      } else if (e.status === 422) {
        setError("Automatic renewal requires an agent-managed target.");
      } else {
        setError(e.message || "Failed to request renewal");
      }
      setLoading(false);
    }
  };

  return (
    <div
      className="modal-bg"
      onClick={(e) => e.target === e.currentTarget && onClose()}
      aria-modal="true"
      role="dialog"
      aria-labelledby="renew-modal-title"
    >
      <div className="modal">
        <div className="modal-title" id="renew-modal-title">Request Certificate Renewal</div>
        <p className="modal-sub">
          The agent will generate a fresh CSR on the target host. The private key
          never leaves the host — only the signed certificate is returned.
        </p>

        {error && <div className="alert alert-error" role="alert">{error}</div>}

        {providers.length > 0 && (
          <div className="field">
            <label htmlFor="ca-provider">CA Provider</label>
            <select
              id="ca-provider"
              value={caProvider}
              onChange={(e) => setCaProvider(e.target.value)}
            >
              <option value="">Platform default</option>
              {providers.map(p => (
                <option key={p.type} value={p.type}>{p.label}</option>
              ))}
            </select>
          </div>
        )}

        <div className="field">
          <label htmlFor="install-path">
            Install path on agent host{" "}
            <span style={{ color: "var(--muted)", fontWeight: 400 }}>(optional)</span>
          </label>
          <input
            id="install-path"
            value={installPath}
            onChange={(e) => setInstallPath(e.target.value)}
            placeholder="e.g. /etc/ssl/certs/example.pem"
            autoFocus
            onKeyDown={(e) => e.key === "Enter" && !loading && handleSubmit()}
          />
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSubmit} disabled={loading}>
            {loading ? <><Spinner /> Requesting...</> : "Request Renewal"}
          </button>
        </div>
      </div>
    </div>
  );
}
