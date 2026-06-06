export function Toast({ toasts }) {
  const icons = { success: "✓", error: "✕", info: "ℹ" };
  return (
    <div className="toast-wrap" role="status" aria-live="polite" aria-atomic="false">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.type}`}>
          <span
            aria-hidden="true"
            style={{
              color:
                t.type === "success" ? "var(--green)"
                : t.type === "error"   ? "var(--red)"
                : "var(--accent)",
            }}
          >
            {icons[t.type]}
          </span>
          {t.msg}
        </div>
      ))}
    </div>
  );
}
