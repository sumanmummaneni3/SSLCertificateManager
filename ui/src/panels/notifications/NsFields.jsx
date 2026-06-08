// ─── NOTIFICATION SETTINGS — SHARED FIELD PRIMITIVES ─────────────────────────
// Small presentational inputs shared by the org-level and per-target
// notification settings panels (RFC 0008 §3.4).

// ─── NS SWITCH — ACCESSIBLE TOGGLE ───────────────────────────────────────────
export function NsSwitch({ id, checked, onChange, disabled }) {
  return (
    <label className="ns-switch" aria-label={checked ? "Notifications enabled" : "Notifications disabled"}>
      <input
        id={id}
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        disabled={disabled}
      />
      <span className="ns-switch-track" aria-hidden="true" />
      <span className="ns-switch-thumb" aria-hidden="true" />
    </label>
  );
}

// ─── NS NUMBER FIELD ──────────────────────────────────────────────────────────
export function NsNumberField({ id, label, helpText, value, onChange, error, disabled }) {
  return (
    <div className="field ns-field">
      <label htmlFor={id} style={{ display: "block", fontSize: "0.72rem", color: "var(--muted)", marginBottom: 6, letterSpacing: "0.08em", textTransform: "uppercase" }}>
        {label}
      </label>
      <input
        id={id}
        type="number"
        min="1"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        aria-invalid={!!error}
        aria-describedby={error ? `${id}-err` : helpText ? `${id}-help` : undefined}
        style={{
          width: "100%",
          background: "var(--surface2)",
          border: `1px solid ${error ? "var(--red)" : "var(--border2)"}`,
          borderRadius: "var(--radius)",
          color: "var(--text)",
          fontFamily: "var(--font-mono)",
          fontSize: "0.85rem",
          padding: "10px 14px",
          outline: "none",
          opacity: disabled ? 0.5 : 1,
        }}
      />
      {helpText && !error && (
        <div id={`${id}-help`} style={{ fontSize: "0.68rem", color: "var(--muted)", marginTop: 3 }}>{helpText}</div>
      )}
      {error && (
        <div id={`${id}-err`} className="ns-field-error" role="alert">{error}</div>
      )}
    </div>
  );
}
