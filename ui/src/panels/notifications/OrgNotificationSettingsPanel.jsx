import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { validateNotificationSettings } from "@/lib/validation.js";
import { Spinner } from "@/components/index.js";
import { NsSwitch, NsNumberField } from "./NsFields.jsx";

// ─── ORG NOTIFICATION SETTINGS PANEL ─────────────────────────────────────────
/**
 * Displays and allows editing of the organization-level notification settings.
 * Consumed by SettingsView.
 * API: GET/PUT /api/v1/org/notification-settings
 */
export function OrgNotificationSettingsPanel({ token, me, toast }) {
  const canWrite = me == null || me?.user?.role === "ADMIN" || me?.platformAdmin === true;

  const [settings, setSettings]   = useState(null);
  const [form, setForm]           = useState({});
  const [loading, setLoading]     = useState(true);
  const [saving, setSaving]       = useState(false);
  const [dirty, setDirty]         = useState(false);
  const [errors, setErrors]       = useState({});

  useEffect(() => {
    let cancelled = false;
    api.getOrgNotificationSettings(token)
      .then((data) => {
        if (!cancelled) {
          setSettings(data);
          setForm({
            enabled:      data.enabled,
            warningDays:  String(data.warningDays),
            criticalDays: String(data.criticalDays),
            dedupHours:   String(data.dedupHours),
          });
        }
      })
      .catch((e) => {
        if (!cancelled) toast("Failed to load notification settings: " + e.message, "error");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token, toast]);

  const setField = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }));
    setDirty(true);
    if (errors[field]) setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const handleSave = async (ev) => {
    ev.preventDefault();
    const errs = validateNotificationSettings(form);
    if (Object.keys(errs).length) { setErrors(errs); return; }
    setSaving(true);
    try {
      const updated = await api.putOrgNotificationSettings({
        enabled:      form.enabled,
        warningDays:  parseInt(form.warningDays, 10),
        criticalDays: parseInt(form.criticalDays, 10),
        dedupHours:   parseInt(form.dedupHours, 10),
      }, token);
      setSettings(updated);
      setForm({
        enabled:      updated.enabled,
        warningDays:  String(updated.warningDays),
        criticalDays: String(updated.criticalDays),
        dedupHours:   String(updated.dedupHours),
      });
      setDirty(false);
      setErrors({});
      toast("Notification settings saved", "success");
    } catch (e) {
      toast("Save failed: " + e.message, "error");
    } finally {
      setSaving(false);
    }
  };

  const handleReset = () => {
    if (!settings) return;
    setForm({
      enabled:      settings.enabled,
      warningDays:  String(settings.warningDays),
      criticalDays: String(settings.criticalDays),
      dedupHours:   String(settings.dedupHours),
    });
    setDirty(false);
    setErrors({});
  };

  if (loading) {
    return (
      <div className="ns-panel" aria-busy="true">
        <div className="ns-panel-title">Notification Settings</div>
        <div style={{ display: "flex", alignItems: "center", gap: 8, color: "var(--muted)", fontSize: "0.8rem" }}>
          <Spinner /> Loading notification settings...
        </div>
      </div>
    );
  }

  return (
    <form onSubmit={handleSave} noValidate aria-label="Organisation notification settings">
      <div className="ns-panel">
        <div className="ns-panel-title">
          <span>Notification Settings</span>
          <span style={{ fontSize: "0.68rem", fontWeight: 400, color: "var(--muted)" }}>
            Org default — applies to all targets unless overridden
          </span>
        </div>

        {/* Master enable/disable */}
        <div className="ns-toggle-row">
          <div>
            <div className="ns-toggle-label">Enable expiry notifications</div>
            <div className="ns-toggle-sub">
              When disabled, no alerts are sent for any target in this organisation.
            </div>
          </div>
          <NsSwitch
            id="org-ns-enabled"
            checked={!!form.enabled}
            onChange={(v) => setField("enabled", v)}
            disabled={!canWrite}
          />
        </div>

        {/* Threshold fields */}
        <fieldset style={{ border: "none", padding: 0, margin: 0 }} disabled={!form.enabled || !canWrite}>
          <legend style={{ fontSize: "0.7rem", color: "var(--muted)", textTransform: "uppercase", letterSpacing: "0.08em", marginBottom: "0.75rem" }}>
            Thresholds
          </legend>
          <div className="ns-fields-grid">
            <NsNumberField
              id="org-ns-warning-days"
              label="Warning days"
              helpText="Alert when cert expires within this many days"
              value={form.warningDays ?? ""}
              onChange={(v) => setField("warningDays", v)}
              error={errors.warningDays}
              disabled={!canWrite || !form.enabled}
            />
            <NsNumberField
              id="org-ns-critical-days"
              label="Critical days"
              helpText="Must be less than warning days"
              value={form.criticalDays ?? ""}
              onChange={(v) => setField("criticalDays", v)}
              error={errors.criticalDays}
              disabled={!canWrite || !form.enabled}
            />
            <NsNumberField
              id="org-ns-dedup-hours"
              label="Dedup hours"
              helpText="Min hours between repeat alerts per cert"
              value={form.dedupHours ?? ""}
              onChange={(v) => setField("dedupHours", v)}
              error={errors.dedupHours}
              disabled={!canWrite || !form.enabled}
            />
          </div>
        </fieldset>

        {canWrite && (
          <div className="ns-actions">
            <button
              type="submit"
              className="btn btn-primary btn-sm"
              style={{ width: "auto" }}
              disabled={saving || !dirty}
            >
              {saving ? <><Spinner /> Saving...</> : "Save"}
            </button>
            {dirty && (
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                style={{ width: "auto" }}
                onClick={handleReset}
                disabled={saving}
              >
                Discard
              </button>
            )}
          </div>
        )}
      </div>
    </form>
  );
}
