import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function MspClientModal({ token, client, onClose, onSaved }) {
  const isEdit = !!client;
  const [name, setName]     = useState(client?.name || "");
  const [email, setEmail]   = useState(client?.contactEmail || "");
  const [country, setCountry] = useState(client?.country || "");
  const [loading, setLoading] = useState(false);
  const [error, setError]   = useState("");

  const handleSubmit = async () => {
    if (!name.trim()) { setError("Organization name is required"); return; }
    setError(""); setLoading(true);
    try {
      const data = { name: name.trim(), contactEmail: email.trim() || null, country: country.trim() || null };
      if (isEdit) {
        await api.msp.updateClient(client.id, data, token);
      } else {
        await api.msp.createClient(data, token);
      }
      onSaved();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="msp-client-modal-title">
        <div className="modal-title" id="msp-client-modal-title">
          {isEdit ? "Edit Client Org" : "Add Client Org"}
        </div>
        <p className="modal-sub">
          {isEdit ? "Update the client organisation details." : "Create a new client organisation under your MSP account."}
        </p>

        {error && <div className="alert alert-error" role="alert">⚠ {error}</div>}

        <div className="field">
          <label htmlFor="msp-client-name">Organization Name <span style={{ color: "var(--red)" }}>*</span></label>
          <input
            id="msp-client-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleSubmit()}
            placeholder="e.g. Acme Corporation"
            autoFocus
          />
        </div>
        <div className="field">
          <label htmlFor="msp-client-email">Contact Email</label>
          <input
            id="msp-client-email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="contact@client.com"
          />
        </div>
        <div className="field">
          <label htmlFor="msp-client-country">Country</label>
          <input
            id="msp-client-country"
            value={country}
            onChange={(e) => setCountry(e.target.value)}
            placeholder="US"
          />
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSubmit} disabled={loading || !name.trim()}>
            {loading ? <><Spinner /> Saving...</> : (isEdit ? "Save Changes" : "Create")}
          </button>
        </div>
      </div>
    </div>
  );
}
