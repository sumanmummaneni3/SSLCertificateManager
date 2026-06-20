// ─── HELPERS ─────────────────────────────────────────────────────────────────
// Pure utility functions shared across panels and components.
// No React imports — safe to import from hooks, plain JS modules, and components.

/** Maps a certificate status string to a CSS badge class suffix. */
export const statusColor = (s) =>
  ({
    VALID:       "green",
    EXPIRING:    "yellow",
    EXPIRED:     "red",
    UNREACHABLE: "orange",
    REVOKED:     "revoked",
    INVALID:     "invalid",
    UNKNOWN:     "unknown",
  }[s] || "unknown");

/**
 * Returns the badge type for a certificate given its status and onHold flag.
 * When status is REVOKED and onHold is true the badge uses "on-hold" (amber/reversible).
 */
export const certBadgeType = (status, onHold) => {
  if (status === "REVOKED" && onHold) return "on-hold";
  return statusColor(status);
};

/**
 * Returns a human-readable label for a certificate status + onHold combination.
 */
export const certStatusLabel = (status, onHold) => {
  if (status === "REVOKED" && onHold) return "Suspended (on hold)";
  return status || "—";
};

// ─── REVOCATION REASON HUMANIZATION ──────────────────────────────────────────
/** Human-readable label for a revocationReason enum string from §5.3. */
export const humanizeRevocationReason = (reason) => ({
  KEY_COMPROMISE:         "Key Compromise",
  CA_COMPROMISE:          "CA Compromise",
  AFFILIATION_CHANGED:    "Affiliation Changed",
  SUPERSEDED:             "Superseded",
  CESSATION_OF_OPERATION: "Cessation of Operation",
  CERTIFICATE_HOLD:       "Certificate Hold (temporary)",
  REMOVE_FROM_CRL:        "Removed from CRL",
  PRIVILEGE_WITHDRAWN:    "Privilege Withdrawn",
  AA_COMPROMISE:          "AA Compromise",
  UNSPECIFIED:            "Unspecified",
}[reason] || reason || "—");

/** Returns true if the reason code warrants a HIGH-PRIORITY callout. */
export const isHighPriorityReason = (reason) =>
  reason === "KEY_COMPROMISE" || reason === "CA_COMPROMISE";

// ─── CHAIN VALIDATION ERROR HUMANIZATION ─────────────────────────────────────
/** Human-readable label for a chainValidationError enum string from RFC §5.1. */
export const humanizeChainError = (code) => {
  if (!code) return null;
  if (code.startsWith("CHAIN_ERROR:")) return `Chain Error: ${code.slice(12)}`;
  return ({
    UNTRUSTED_ANCHOR:          "Untrusted Root CA",
    INCOMPLETE_CHAIN:          "Incomplete Chain (missing intermediates)",
    SELF_SIGNED:               "Self-Signed Certificate",
    EXPIRED_CHAIN_ELEMENT:     "Expired Intermediate or Root",
    PATH_LEN_VIOLATION:        "Path Length Constraint Violated",
    NAME_CONSTRAINT_VIOLATION: "Name Constraint Violation",
    BASIC_CONSTRAINT_VIOLATION:"Basic Constraint Violation",
    SIGNATURE_INVALID:         "Signature Verification Failed",
    WEAK_ALGORITHM:            "Weak Signature Algorithm",
  }[code] || code);
};

// ─── REVOCATION SOURCE HUMANIZATION ──────────────────────────────────────────
export const humanizeRevocationSource = (source) => ({
  OCSP_STAPLED: "OCSP (Stapled)",
  OCSP:         "OCSP",
  CRL:          "CRL",
  NONE:         "None",
}[source] || source || "—");

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
