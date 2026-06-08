// ─── HELPERS ─────────────────────────────────────────────────────────────────
// Pure utility functions shared across panels and components.
// No React imports — safe to import from hooks, plain JS modules, and components.

/** Maps a certificate status string to a CSS badge class suffix. */
export const statusColor = (s) =>
  ({ VALID: "green", EXPIRING: "yellow", EXPIRED: "red", UNREACHABLE: "orange" }[s] || "unknown");

/** Maps a host type string to a CSS badge class suffix (lowercased). */
export const hostTypeColor = (t) => t?.toLowerCase() || "unknown";

/** Formats an ISO date string as "DD Mon YYYY" (en-GB locale). Returns "—" for falsy input. */
export const fmtDate = (iso) =>
  iso ? new Date(iso).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" }) : "—";

/** Returns the CSS color variable for a days-remaining value. */
export const daysColor = (d) =>
  d < 0 ? "var(--red)" : d <= 7 ? "var(--red)" : d <= 30 ? "var(--yellow)" : "var(--green)";

/** Returns the CSS width percentage string for a days-remaining progress bar (max 365 days). */
export const daysWidth = (d) => `${Math.min(100, Math.max(0, (d / 365) * 100))}%`;

/** Returns a human-readable relative time string ("Just now", "5m ago", "2h ago", "3d ago"). */
export const fmtRelative = (iso) => {
  if (!iso) return "Never";
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "Just now";
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
};
