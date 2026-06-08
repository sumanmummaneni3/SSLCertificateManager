import { useState, useEffect, useCallback } from "react";
import { api } from "@/lib/api.js";
import { validateNotificationSettings } from "@/lib/validation.js";
import { Spinner } from "@/components/index.js";
import { NsSwitch, NsNumberField } from "./NsFields.jsx";

// ─── TARGET NOTIFICATION SETTINGS PANEL ──────────────────────────────────────
/**
 * Shows per-target notification settings inside the target notification modal.
 * When the target inherits org defaults, shows inherited values read-only with
 * an "Override for this target" button. When overridden, shows editable fields
 * with a "Reset to org default" (DELETE) affordance.
 *
 * API:
 *   GET    /api/v1/targets/{id}/notification-settings
 *   PUT    /api/v1/targets/{id}/notification-settings
 *   DELETE /api/v1/targets/{id}/notification-settings
 */
function TargetNotificationSettingsPanel({ targetId, token, me, toast }) {
  const canWrite = me == null || me?.permissions?.canWriteTargets ||
    me?.user?.role === "ADMIN" || me?.platformAdmin === true;

  const [settings, setSettings]   = useState(null);
  const [loading, setLoading]     = useState(true);
  const [saving, setSaving]       = useState(false);
  const [resetting, setResetting] = useState(false);
  const [overrideMode, setOverrideMode] = useState(false); // true = editing override fields
  const [form, setForm]           = useState({});
  const [dirty, setDirty]         = useState(false);
  const [errors, setErrors]       = useState({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.getTargetNotificationSettings(targetId, token);
      setSettings(data);
      // If there's already an override (inherited === false or absent), go straight to edit mode
      const hasOverride = data.inherited === false;
      setOverrideMode(hasOverride);
      setForm({
        enabled:      data.enabled,
        warningDays:  String(data.warningDays),
        criticalDays: String(data.criticalDays),
        dedupHours:   String(data.dedupHours),
      });
      setDirty(false);
      setErrors({});
    } catch (e) {
      toast("Failed to load target notification settings: " + e.message, "error");
    } finally {
      setLoading(false);
    }
  }, [targetId, token, toast]);

  useEffect(() => { load(); }, [load]);

  const setField = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }));
    setDirty(true);
    if (errors[field]) setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const handleActivateOverride = () => {
    setOverrideMode(true);
    setDirty(true);
  };

  const handleSave = async (ev) => {
    ev.preventDefault();
    const errs = validateNotificationSettings(form);
    if (Object.keys(errs).length) { setErrors(errs); return; }
    setSaving(true);
    try {
      const updated = await api.putTargetNotificationSettings(targetId, {
        enabled:      form.enabled,
        warningDays:  parseInt(form.warningDays, 10),
        criticalDays: parseInt(form.criticalDays, 10),
        dedupHours:   parseInt(form.dedupHours, 10),
      }, token);
      setSettings({ ...updated, inherited: false });
      setForm({
        enabled:      updated.enabled,
        warningDays:  String(updated.warningDays),
        criticalDays: String(updated.criticalDays),
        dedupHours:   String(updated.dedupHours),
      });
      setDirty(false);
      setErrors({});
      toast("Target notification settings saved", "success");
    } catch (e) {
      toast("Save failed: " + e.message, "error");
    } finally {
      setSaving(false);
    }
  };

  const handleResetToOrgDefault = async () => {
    setResetting(true);
    try {
      await api.deleteTargetNotificationSettings(targetId, token);
      toast("Target override cleared — reverted to org default", "success");
      await load();
    } catch (e) {
      toast("Reset failed: " + e.message, "error");
    } finally {
      setResetting(false);
    }
  };

  const handleDiscardOverride = () => {
    // Cancel new-override draft: revert UI to inherited state
    setOverrideMode(false);
    setDirty(false);
    setErrors({});
    if (settings) {
      setForm({
        enabled:      settings.enabled,
        warningDays:  String(settings.warningDays),
        criticalDays: String(settings.criticalDays),
        dedupHours:   String(settings.dedupHours),
      });
    }
  };

  if (loading) {
    return (
      <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "1rem 0", color: "var(--muted)", fontSize: "0.8rem" }} aria-busy="true">
        <Spinner /> Loading notification settings...
      </div>
    );
  }

  const isInherited = settings?.inherited !== false && !overrideMode;

  return (
    <form onSubmit={handleSave} noValidate aria-label="Target notification settings">
      {/* Inherited banner */}
      {isInherited && (
        <div className="ns-inherited-banner" role="status">
          <span aria-hidden="true" style={{ fontSize: "1rem" }}>i</span>
          <span className="ns-inherited-label">
            Inherited from organization — the values below are the effective org defaults.
          </span>
        </div>
      )}

      {/* Effective / editable values */}
      {isInherited ? (
        /* Read-only inherited view */
        <>
          <div className="ns-readonly-grid">
            <span className="ns-readonly-key">Notifications</span>
            <span className="ns-readonly-val" style={{ color: settings?.enabled ? "var(--green)" : "var(--muted)" }}>
              {settings?.enabled ? "Enabled" : "Disabled"}
            </span>

            <span className="ns-readonly-key">Warning days</span>
            <span className="ns-readonly-val">{settings?.warningDays ?? "—"}</span>

            <span className="ns-readonly-key">Critical days</span>
            <span className="ns-readonly-val">{settings?.criticalDays ?? "—"}</span>

            <span className="ns-readonly-key">Dedup hours</span>
            <span className="ns-readonly-val">{settings?.dedupHours ?? "—"}</span>

            {/* TODO: Surface last-alert timestamp once the API exposes it.
                The CertificateRecord entity has lastAlertSentAt (RFC 0008 §2.3), but
                /api/v1/targets/{id}/notification-settings does not currently include it
                in the response shape. Request the backend-engineer to add
                lastAlertSentAt (ISO-8601) to both GET responses so the UI can explain
                why a force-scan produced no email (dedup gate). */}
          </div>
          {canWrite && (
            <div className="ns-actions">
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                style={{ width: "auto" }}
                onClick={handleActivateOverride}
              >
                Override for this target
              </button>
            </div>
          )}
        </>
      ) : (
        /* Override editing view */
        <>
          {settings?.inherited === false && (
            <div className="alert alert-info" role="status" style={{ marginBottom: "0.75rem", fontSize: "0.78rem" }}>
              This target has its own notification settings — org defaults do not apply.
            </div>
          )}

          <div className="ns-toggle-row">
            <div>
              <div className="ns-toggle-label">Enable notifications</div>
              <div className="ns-toggle-sub">Override the org-level toggle for this target only.</div>
            </div>
            <NsSwitch
              id="tgt-ns-enabled"
              checked={!!form.enabled}
              onChange={(v) => setField("enabled", v)}
              disabled={!canWrite}
            />
          </div>

          <fieldset style={{ border: "none", padding: 0, margin: 0 }} disabled={!form.enabled || !canWrite}>
            <legend style={{ fontSize: "0.7rem", color: "var(--muted)", textTransform: "uppercase", letterSpacing: "0.08em", marginBottom: "0.75rem" }}>
              Thresholds
            </legend>
            <div className="ns-fields-grid">
              <NsNumberField
                id="tgt-ns-warning-days"
                label="Warning days"
                helpText="Alert when cert expires within N days"
                value={form.warningDays ?? ""}
                onChange={(v) => setField("warningDays", v)}
                error={errors.warningDays}
                disabled={!canWrite || !form.enabled}
              />
              <NsNumberField
                id="tgt-ns-critical-days"
                label="Critical days"
                helpText="Must be less than warning days"
                value={form.criticalDays ?? ""}
                onChange={(v) => setField("criticalDays", v)}
                error={errors.criticalDays}
                disabled={!canWrite || !form.enabled}
              />
              <NsNumberField
                id="tgt-ns-dedup-hours"
                label="Dedup hours"
                helpText="Min hours between alerts for this cert"
                value={form.dedupHours ?? ""}
                onChange={(v) => setField("dedupHours", v)}
                error={errors.dedupHours}
                disabled={!canWrite || !form.enabled}
              />
            </div>
          </fieldset>

          {canWrite && (
            <div className="ns-actions" style={{ flexWrap: "wrap" }}>
              <button
                type="submit"
                className="btn btn-primary btn-sm"
                style={{ width: "auto" }}
                disabled={saving || !dirty}
              >
                {saving ? <><Spinner /> Saving...</> : "Save override"}
              </button>
              {/* Cancel draft — only when the override hasn't been persisted yet */}
              {settings?.inherited !== false && (
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  style={{ width: "auto" }}
                  onClick={handleDiscardOverride}
                  disabled={saving}
                >
                  Cancel
                </button>
              )}
              {/* Reset — only when there is a persisted override */}
              {settings?.inherited === false && (
                <button
                  type="button"
                  className="btn btn-danger btn-sm"
                  style={{ width: "auto" }}
                  onClick={handleResetToOrgDefault}
                  disabled={resetting || saving}
                >
                  {resetting ? <><Spinner /> Resetting...</> : "Reset to org default"}
                </button>
              )}
            </div>
          )}
        </>
      )}
    </form>
  );
}

// ─── TARGET NOTIFICATION MODAL ────────────────────────────────────────────────
/**
 * Modal wrapper for viewing and editing per-target notification settings.
 * Launched from the Targets table row actions.
 */
export function TargetNotificationModal({ target, token, me, toast, onClose }) {
  return (
    <div
      className="modal-bg"
      onClick={(e) => e.target === e.currentTarget && onClose()}
    >
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="tgt-ns-modal-title"
        style={{ maxWidth: 520 }}
      >
        <div className="modal-title" id="tgt-ns-modal-title">
          Notification Settings
        </div>
        <p className="modal-sub">
          {target.host}:{target.port} — configure expiry alert thresholds for this target.
        </p>

        <TargetNotificationSettingsPanel
          targetId={target.id}
          token={token}
          me={me}
          toast={toast}
        />

        <div style={{ marginTop: "1.25rem" }}>
          <button className="btn btn-secondary" onClick={onClose} style={{ width: "100%" }}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
