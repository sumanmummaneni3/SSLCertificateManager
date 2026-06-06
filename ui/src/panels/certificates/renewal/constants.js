export const RENEWAL_STATUS_LABELS = {
  REQUESTED:       "Initializing renewal...",
  CSR_PENDING:     "Waiting for agent to generate CSR...",
  CSR_RECEIVED:    "CSR received, submitting to CA...",
  CA_PENDING:      "Certificate request submitted to CA...",
  CA_ISSUED:       "Certificate issued, preparing delivery...",
  STORED:          "Certificate stored, queuing delivery...",
  DELIVERY_QUEUED: "Waiting for agent to install certificate...",
  DELIVERED:       "Certificate successfully installed!",
  FAILED:          "Renewal failed",
  CANCELLED:       "Renewal cancelled",
};

export const RENEWAL_TERMINAL    = new Set(["DELIVERED", "FAILED", "CANCELLED"]);
export const RENEWAL_HAS_PACKAGE = new Set(["STORED", "DELIVERY_QUEUED", "DELIVERED"]);

export function renewalBadgeClass(status) {
  return `badge renewal-badge-${(status || "unknown").toLowerCase()}`;
}
