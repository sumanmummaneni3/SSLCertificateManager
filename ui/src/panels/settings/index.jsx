import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { Spinner, Badge } from "@/components/index.js";
import { MspUpgradeModal } from "./MspUpgradeModal.jsx";

// Hoisted outside SettingsView so React sees a stable component type across
// renders. Defining it inside the parent caused a new type on every keystroke,
// forcing unmount/remount and losing input focus.
function SettingsField({ label, field, type = "text", placeholder, form, errors, set }) {
  return (
    <div className="field">
      <label>{label}</label>
      <input
        type={type}
        placeholder={placeholder}
        value={form[field] || ""}
        onChange={(e) => set(field, e.target.value)}
      />
      {errors[field] && (
        <div style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }}>
          {errors[field]}
        </div>
      )}
    </div>
  );
}

export function SettingsView({ token, org, me, toast }) {
  const [profile, setProfile] = useState(null);
  const [form, setForm]       = useState({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving]   = useState(false);
  const [dirty, setDirty]     = useState(false);
  const [errors, setErrors]   = useState({});
  const [showMspUpgradeModal, setShowMspUpgradeModal] = useState(false);

  useEffect(() => {
    api.getOrgProfile(token)
      .then((p) => { setProfile(p); setForm(p); })
      .catch((e) => toast("Failed to load profile: " + e.message, "error"))
      .finally(() => setLoading(false));
  }, [token, toast]);

  const set = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }));
    setDirty(true);
    if (errors[field]) setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const validate = () => {
    const e = {};
    if (!form.name?.trim()) e.name = "Organisation name is required";
    if (form.contactEmail && !/\S+@\S+\.\S+/.test(form.contactEmail)) e.contactEmail = "Invalid email";
    return e;
  };

  const handleSave = async (ev) => {
    ev.preventDefault();
    const e = validate();
    if (Object.keys(e).length) { setErrors(e); return; }
    setSaving(true);
    try {
      const updated = await api.updateOrgProfile({
        name: form.name?.trim(),
        addressLine1: form.addressLine1 || null,
        addressLine2: form.addressLine2 || null,
        city: form.city || null,
        stateProvince: form.stateProvince || null,
        postalCode: form.postalCode || null,
        country: form.country || null,
        phone: form.phone || null,
        contactEmail: form.contactEmail || null,
      }, token);
      setProfile(updated);
      setForm(updated);
      setDirty(false);
      toast("Organisation profile saved", "success");
    } catch (err) {
      toast("Save failed: " + err.message, "error");
    } finally {
      setSaving(false);
    }
  };

  const handleReset = () => { setForm(profile); setDirty(false); setErrors({}); };

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Settings</div>
          <div className="page-sub">Organisation profile and configuration</div>
        </div>
      </div>
      <div className="page-content">
        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading settings...</span></div>
        ) : (
          <form onSubmit={handleSave} style={{ maxWidth: 640 }}>
            <div className="cert-detail" style={{ marginBottom: "1.5rem" }}>
              <div style={{ fontSize: "0.7rem", color: "var(--muted)", textTransform: "uppercase",
                            letterSpacing: "0.1em", marginBottom: "1rem" }}>Organisation Profile</div>

              <SettingsField label="Organisation Name *" field="name" placeholder="Acme Corp" form={form} errors={errors} set={set} />
              <SettingsField label="Contact Email" field="contactEmail" type="email" placeholder="admin@acme.com" form={form} errors={errors} set={set} />
              <SettingsField label="Phone" field="phone" placeholder="+1 555 000 0000" form={form} errors={errors} set={set} />

              <div style={{ fontSize: "0.7rem", color: "var(--muted)", textTransform: "uppercase",
                            letterSpacing: "0.1em", margin: "1.25rem 0 0.75rem" }}>Address</div>

              <SettingsField label="Address Line 1" field="addressLine1" placeholder="123 Main Street" form={form} errors={errors} set={set} />
              <SettingsField label="Address Line 2" field="addressLine2" placeholder="Suite 400" form={form} errors={errors} set={set} />
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
                <SettingsField label="City" field="city" placeholder="San Francisco" form={form} errors={errors} set={set} />
                <SettingsField label="State / Province" field="stateProvince" placeholder="CA" form={form} errors={errors} set={set} />
              </div>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
                <SettingsField label="Postal Code" field="postalCode" placeholder="94105" form={form} errors={errors} set={set} />
                <SettingsField label="Country" field="country" placeholder="US" form={form} errors={errors} set={set} />
              </div>
            </div>

            <div className="cert-detail" style={{ marginBottom: "1.5rem" }}>
              <div style={{ fontSize: "0.7rem", color: "var(--muted)", textTransform: "uppercase",
                            letterSpacing: "0.1em", marginBottom: "0.75rem" }}>Plan & Quota</div>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "1rem", fontSize: "0.82rem" }}>
                <div>
                  <div style={{ color: "var(--muted)", marginBottom: 4 }}>Subscription</div>
                  <Badge type={profile?.status === "ACTIVE" ? "active" : "pending"}>{profile?.status || "—"}</Badge>
                </div>
                <div>
                  <div style={{ color: "var(--muted)", marginBottom: 4 }}>Certificate Quota</div>
                  <span style={{ fontFamily: "var(--font-mono)", fontWeight: 600 }}>{profile?.maxCertificateQuota ?? "—"}</span>
                </div>
              </div>
              {org?.orgType === "SINGLE" && (
                <div style={{ marginTop: "1rem", paddingTop: "0.75rem", borderTop: "1px solid var(--border)" }}>
                  <div style={{ color: "var(--muted)", fontSize: "0.78rem", marginBottom: "0.5rem" }}>Account Type</div>
                  <div style={{ display: "flex", alignItems: "center", gap: "0.75rem" }}>
                    <Badge type="pending">Standard</Badge>
                    {me?.permissions?.mspUpgradePending ? (
                      <Badge type="pending">MSP Upgrade Pending</Badge>
                    ) : (
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        onClick={() => setShowMspUpgradeModal(true)}
                      >
                        Upgrade to MSP
                      </button>
                    )}
                  </div>
                </div>
              )}
            </div>

            <div style={{ display: "flex", gap: "0.5rem" }}>
              <button type="submit" className="btn btn-primary" disabled={saving || !dirty}>
                {saving ? <Spinner /> : "Save Changes"}
              </button>
              {dirty && (
                <button type="button" className="btn btn-secondary" onClick={handleReset} disabled={saving}>
                  Discard
                </button>
              )}
            </div>
          </form>
        )}
      </div>
      {showMspUpgradeModal && (
        <MspUpgradeModal
          token={token}
          onClose={() => setShowMspUpgradeModal(false)}
          toast={toast}
        />
      )}
    </>
  );
}
