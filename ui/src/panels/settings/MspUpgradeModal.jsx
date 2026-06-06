import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function MspUpgradeModal({ token, onClose, toast }) {
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async () => {
    setError(""); setLoading(true);
    try {
      await api.call("POST", "/api/v1/org/request-msp-upgrade", { reason: reason.trim() || null }, token);
      toast("MSP upgrade request submitted. Our team will review it shortly.", "success");
      onClose();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="msp-upgrade-title">
        <div className="modal-title" id="msp-upgrade-title">Upgrade to MSP</div>
        <p className="modal-sub">
          Request an upgrade to an MSP account to manage certificates across multiple client organizations.
          Our team will review your request and get in touch within one business day.
        </p>
        {error && <div className="alert alert-error" role="alert">⚠ {error}</div>}
        <div className="field">
          <label htmlFor="msp-upgrade-reason">Reason / Use case (optional)</label>
          <textarea
            id="msp-upgrade-reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Tell us about your use case..."
            rows={4}
            style={{
              width: "100%",
              background: "var(--surface2)",
              border: "1px solid var(--border2)",
              borderRadius: "var(--radius)",
              color: "var(--text)",
              fontFamily: "var(--font-head)",
              fontSize: "0.85rem",
              padding: "10px 14px",
              outline: "none",
              resize: "vertical",
            }}
          />
        </div>
        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSubmit} disabled={loading}>
            {loading ? <><Spinner /> Submitting...</> : "Submit Request"}
          </button>
        </div>
      </div>
    </div>
  );
}
