import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function MspUpgradeModal({ token, onClose, toast }) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const handleUpgrade = async () => {
    setError(""); setLoading(true);
    try {
      await api.call("POST", "/api/v1/org/upgrade-msp", null, token);
      toast("You're now an MSP! Reloading...", "success");
      // Reload so the app refetches `me`/`org` and the MSP nav appears.
      setTimeout(() => window.location.reload(), 600);
    } catch (e) {
      setError(e.message);
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="msp-upgrade-title">
        <div className="modal-title" id="msp-upgrade-title">Upgrade to MSP</div>
        <p className="modal-sub">
          Switch to an MSP account to manage certificates across multiple client
          organizations. This is instant — no review required.
        </p>
        <div className="alert alert-info" style={{ marginBottom: "1rem" }}>
          <span aria-hidden="true">ℹ</span>
          <span>
            Your free tier covers up to <strong>10 certificates</strong> across all client
            organizations. Scanning beyond that requires a paid quota increase.
          </span>
        </div>
        {error && <div className="alert alert-error" role="alert">⚠ {error}</div>}
        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
          <button className="btn btn-primary" onClick={handleUpgrade} disabled={loading}>
            {loading ? <><Spinner /> Upgrading...</> : "Upgrade Now"}
          </button>
        </div>
      </div>
    </div>
  );
}
