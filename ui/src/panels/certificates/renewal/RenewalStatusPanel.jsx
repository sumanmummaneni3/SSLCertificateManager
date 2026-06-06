import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { fmtDate } from "@/lib/helpers.js";
import { Spinner } from "@/components/index.js";
import { RENEWAL_STATUS_LABELS, RENEWAL_TERMINAL, RENEWAL_HAS_PACKAGE, renewalBadgeClass } from "./constants.js";

export function RenewalStatusPanel({ renewal: initialRenewal, token }) {
  const [renewal, setRenewal]   = useState(initialRenewal);
  const renewalId               = renewal?.id;
  const status                  = renewal?.status;
  const isTerminal              = RENEWAL_TERMINAL.has(status);

  // Poll every 5 s while non-terminal
  useEffect(() => {
    if (!renewalId || isTerminal) return;
    const id = setInterval(async () => {
      try {
        const updated = await api.getRenewal(renewalId, token);
        setRenewal(updated);
      } catch {
        // Non-fatal — keep polling
      }
    }, 5000);
    return () => clearInterval(id);
  }, [renewalId, isTerminal, token]);

  if (!renewal) return null;

  const label       = RENEWAL_STATUS_LABELS[status] || status;
  const hasPackage  = RENEWAL_HAS_PACKAGE.has(status);
  const isFailed    = status === "FAILED";
  const isDelivered = status === "DELIVERED";
  const isCancelled = status === "CANCELLED";

  return (
    <div className="renewal-panel" aria-live="polite">
      <div className="renewal-panel-title">
        <span aria-hidden="true" style={{ color: isDelivered ? "var(--green)" : isFailed ? "var(--red)" : "var(--accent)" }}>
          {isDelivered ? "✓" : isFailed ? "✕" : isCancelled ? "◌" : "◎"}
        </span>
        Current Renewal
        <span className={renewalBadgeClass(status)}>{status}</span>
      </div>

      <div
        className={`renewal-status-row${isDelivered ? " terminal-success" : isFailed ? " terminal-failed" : isCancelled ? " terminal-cancel" : ""}`}
      >
        {!isTerminal && <Spinner />}
        <span>{label}</span>
      </div>

      {isFailed && renewal.failureReason && (
        <pre className="renewal-failure-detail" aria-label="Failure detail">{renewal.failureReason}</pre>
      )}

      {hasPackage && (
        <a
          href={api.renewalPackageUrl(renewalId)}
          download
          className="btn btn-secondary btn-sm"
          style={{ display: "inline-flex", alignItems: "center", gap: 6, marginTop: "0.75rem", textDecoration: "none", width: "auto" }}
          aria-label="Download renewed certificate package"
        >
          ↓ Download Certificate
        </a>
      )}

      {renewal.createdAt && (
        <div style={{ marginTop: "0.5rem", fontSize: "0.72rem", color: "var(--muted)" }}>
          Started {fmtDate(renewal.createdAt)}
          {renewal.caProvider && renewal.caProvider !== "NONE" && ` · CA: ${renewal.caProvider}`}
        </div>
      )}
    </div>
  );
}
