// ─── VALIDATION HELPERS ──────────────────────────────────────────────────────
// Input validation utilities used by agent-creation and target-creation flows.
// No React imports — safe to use in both component and non-component contexts.

/** Valid agent name: 3–64 chars, alphanumeric + space, underscore, dot, hyphen. */
// Hyphen at end of character class — no escape needed
export const AGENT_NAME_RE = /^[A-Za-z0-9 _.-]{3,64}$/;

/** Valid IPv4 CIDR notation, e.g. 192.168.1.0/24. */
export const CIDR_RE = /^(\d{1,3}\.){3}\d{1,3}\/([0-9]|[1-2]\d|3[0-2])$/;

/**
 * Validates a comma-separated list of CIDR strings.
 * Returns an error message string on failure, or null on success.
 */
export function validateCidrs(raw) {
  if (!raw.trim()) return "At least one CIDR is required";
  const parts = raw.split(",").map((s) => s.trim()).filter(Boolean);
  for (const p of parts) {
    if (!CIDR_RE.test(p)) return `Invalid CIDR: "${p}" — use format 192.168.1.0/24`;
    const octets = p.split("/")[0].split(".");
    if (octets.some((o) => parseInt(o) > 255)) return `Invalid IP in CIDR: "${p}"`;
  }
  return null;
}

/**
 * Returns true if the host string is an RFC 1918 private IP address.
 * Used to auto-flag targets as private when the user types a private IP.
 */
export function isRfc1918(h) {
  const s = h.trim();
  return s.startsWith("192.168.") || s.startsWith("10.") || s.startsWith("127.") ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(s);
}

/**
 * Validates notification settings form values.
 * Mirrors the server-side CHECK constraint from RFC 0008 §3.1:
 *   critical_days > 0
 *   warning_days > critical_days
 *   dedup_hours >= 1
 *
 * Returns an errors object (empty = valid).
 */
export function validateNotificationSettings({ warningDays, criticalDays, dedupHours }) {
  const errs = {};
  const w = parseInt(warningDays, 10);
  const c = parseInt(criticalDays, 10);
  const d = parseInt(dedupHours, 10);

  if (isNaN(w) || w < 1) {
    errs.warningDays = "Warning days must be a positive number";
  }
  if (isNaN(c) || c < 1) {
    errs.criticalDays = "Critical days must be a positive number";
  }
  if (!isNaN(w) && !isNaN(c) && c >= w) {
    errs.criticalDays = "Critical days must be less than warning days";
  }
  if (isNaN(d) || d < 1) {
    errs.dedupHours = "Dedup hours must be at least 1";
  }
  return errs;
}
