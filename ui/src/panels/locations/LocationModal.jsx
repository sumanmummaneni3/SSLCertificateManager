import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";
import { PROVIDERS, providerLabel } from "./providers.js";

export function LocationModal({ token, location, onClose, onSaved, toast }) {
  const isEdit = !!location;
  const [name, setName]             = useState(location?.name || "");
  const [provider, setProvider]     = useState(location?.provider || "");
  const [geoRegion, setGeoRegion]   = useState(location?.geoRegion || "");
  const [cloudRegion, setCloudRegion] = useState(location?.cloudRegion || "");
  const [address, setAddress]       = useState(location?.address || "");
  const [loading, setLoading]       = useState(false);
  const [error, setError]           = useState("");

  const handleSave = async () => {
    if (!name.trim())    { setError("Name is required"); return; }
    if (!provider)       { setError("Provider is required"); return; }
    setError(""); setLoading(true);
    const body = {
      name: name.trim(),
      provider,
      geoRegion:   geoRegion.trim()   || null,
      cloudRegion: cloudRegion.trim() || null,
      address:     address.trim()     || null,
    };
    try {
      if (isEdit) {
        await api.updateLocation(location.id, body, token);
        toast("Location updated", "success");
      } else {
        await api.createLocation(body, token);
        toast("Location created", "success");
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
      <div className="modal" role="dialog" aria-modal="true">
        <div className="modal-title">{isEdit ? "Edit Location" : "Add Location"}</div>
        <p className="modal-sub">{isEdit ? "Update location details." : "Group targets under a named site or cloud region."}</p>

        {error && <div className="alert alert-error" role="alert">⚠ {error}</div>}

        <div className="field">
          <label htmlFor="loc-name">Name *</label>
          <input id="loc-name" value={name} onChange={(e) => setName(e.target.value)}
            placeholder="e.g. US-East DC1" autoFocus />
        </div>

        <div className="field">
          <label htmlFor="loc-provider">Provider *</label>
          <select id="loc-provider" value={provider} onChange={(e) => setProvider(e.target.value)}>
            <option value="">— Select provider —</option>
            {PROVIDERS.map((p) => <option key={p} value={p}>{providerLabel(p)}</option>)}
          </select>
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
          <div className="field">
            <label htmlFor="loc-geo">Geo Region</label>
            <input id="loc-geo" value={geoRegion} onChange={(e) => setGeoRegion(e.target.value)}
              placeholder="e.g. Asia Pacific" />
          </div>
          <div className="field">
            <label htmlFor="loc-cloud">Cloud Region</label>
            <input id="loc-cloud" value={cloudRegion} onChange={(e) => setCloudRegion(e.target.value)}
              placeholder="e.g. ap-southeast-2" />
          </div>
        </div>

        <div className="field">
          <label htmlFor="loc-address">Address <span style={{ color: "var(--muted)", fontWeight: 400 }}>(optional)</span></label>
          <input id="loc-address" value={address} onChange={(e) => setAddress(e.target.value)}
            placeholder="Physical address for on-prem / colocation" />
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSave} disabled={loading || !name.trim() || !provider}>
            {loading ? <><Spinner /> Saving...</> : isEdit ? "Save Changes" : "Create Location"}
          </button>
        </div>
      </div>
    </div>
  );
}
