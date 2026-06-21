/**
 * RevocationPanel — RFC 0009 FE-2 + FE-3
 *
 * Shows revocation status, reason (humanized, with KEY/CA_COMPROMISE callout),
 * source, timestamps, chain trust, chain error, and the per-cert deep-check toggle.
 *
 * Props:
 *   cert      — the full cert object (new fields: revocationStatus, revocationReason,
 *               revocationSource, revokedAt, revocationCheckedAt, chainTrusted,
 *               chainValidationError, revocationDeepCheck, onHold)
 *   orgId     — current org UUID (needed for the PATCH endpoint)
 *   token     — bearer token
 *   canWrite  — whether the user has ENGINEER+ role
 *   toast     — (msg, type) => void
 *   onUpdate  — (patchedFields) => void  — called after a successful PATCH so parent
 *               can merge the change into local cert state
 */

import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";
import {
  humanizeRevocationReason,
  humanizeChainError,
  humanizeRevocationSource,
  isHighPriorityReason,
  fmtDate,
  fmtRelative,
} from "@/lib/helpers.js";
import { NsSwitch } from "@/panels/notifications/NsFields.jsx";

// ─── CONSTANTS ────────────────────────────────────────────────────────────────
const REVOCATION_STATUS_LABELS = {
  GOOD:      "Good — not revoked",
  REVOKED:   "Revoked",
  UNKNOWN:   "Unknown — could not be confirmed",
  UNCHECKED: "Not checked — no revocation data available",
};

// ─── REVOCATION STATUS CALLOUT ────────────────────────────────────────────────
function RevocationCallout({ cert }) {
  const { revocationStatus, revocationReason, revokedAt, onHold } = cert;

  if (!revocationStatus || revocationStatus === "UNCHECKED" || revocationStatus === "UNKNOWN") {
    const isUnchecked = !revocationStatus || revocationStatus === "UNCHECKED";
    return (
      <div className="revocation-callout neutral" role="status">
        <span className="revocation-callout-icon" aria-hidden="true">ℹ️</span>
        <div className="revocation-callout-body">
          <div className="revocation-callout-title">
            {isUnchecked ? "Revocation Not Checked" : "Revocation Status Unknown"}
          </div>
          <div className="revocation-callout-detail">
            {isUnchecked
              ? "No revocation information is available for this certificate (no OCSP/CRL endpoints, or self-signed)."
              : "Revocation could not be confirmed — the OCSP responder or CRL was unreachable. The certificate may still be valid."}
          </div>
        </div>
      </div>
    );
  }

  if (revocationStatus === "GOOD") {
    return (
      <div className="revocation-callout" style={{
        background: "rgba(0,230,118,0.08)",
        border: "1px solid rgba(0,230,118,0.25)",
        color: "var(--green)",
      }} role="status">
        <span className="revocation-callout-icon" aria-hidden="true">✅</span>
        <div className="revocation-callout-body">
          <div className="revocation-callout-title">Not Revoked</div>
          <div className="revocation-callout-detail">
            This certificate has a confirmed good (non-revoked) status.
          </div>
        </div>
      </div>
    );
  }

  if (revocationStatus === "REVOKED") {
    const highPriority = isHighPriorityReason(revocationReason);
    const isHold = onHold;

    if (isHold) {
      return (
        <div className="revocation-callout warning" role="alert">
          <span className="revocation-callout-icon" aria-hidden="true">⏸️</span>
          <div className="revocation-callout-body">
            <div className="revocation-callout-title">Suspended (on hold)</div>
            <div className="revocation-callout-detail">
              This certificate is currently on hold — a reversible suspension.
              {revokedAt && ` Suspended since ${fmtDate(revokedAt)}.`}
              {" "}CertGuard will re-check automatically; the status may revert to good.
            </div>
          </div>
        </div>
      );
    }

    return (
      <div className="revocation-callout critical" role="alert" aria-live="assertive">
        <span className="revocation-callout-icon" aria-hidden="true">🚫</span>
        <div className="revocation-callout-body">
          <div className="revocation-callout-title">
            {highPriority
              ? `⚠ CRITICAL: ${humanizeRevocationReason(revocationReason)}`
              : `Certificate Revoked${revocationReason ? ` — ${humanizeRevocationReason(revocationReason)}` : ""}`}
          </div>
          {highPriority && (
            <div className="revocation-callout-detail" style={{ fontWeight: 600 }}>
              This is a security-critical revocation. The private key may be compromised.
              Replace this certificate immediately.
            </div>
          )}
          {revokedAt && (
            <div className="revocation-callout-detail">
              Revoked on {fmtDate(revokedAt)}.
            </div>
          )}
        </div>
      </div>
    );
  }

  return null;
}

// ─── CHAIN CALLOUT ─────────────────────────────────────────────────────────────
function ChainCallout({ cert }) {
  const { chainTrusted, chainValidationError } = cert;

  if (chainTrusted === null || chainTrusted === undefined) {
    return (
      <div className="revocation-callout neutral">
        <span className="revocation-callout-icon" aria-hidden="true">🔗</span>
        <div className="revocation-callout-body">
          <div className="revocation-callout-title">Chain Not Yet Evaluated</div>
          <div className="revocation-callout-detail">
            Chain trust validation has not yet run for this certificate.
          </div>
        </div>
      </div>
    );
  }

  if (chainTrusted) {
    return (
      <div className="revocation-callout" style={{
        background: "rgba(0,230,118,0.08)",
        border: "1px solid rgba(0,230,118,0.25)",
        color: "var(--green)",
      }}>
        <span className="revocation-callout-icon" aria-hidden="true">✅</span>
        <div className="revocation-callout-body">
          <div className="revocation-callout-title">Chain Trusted</div>
          <div className="revocation-callout-detail">
            The full certificate chain is valid and trusted by the configured trust store.
          </div>
        </div>
      </div>
    );
  }

  // chainTrusted === false
  const humanError = humanizeChainError(chainValidationError);
  return (
    <div className="revocation-callout advisory">
      <span className="revocation-callout-icon" aria-hidden="true">⛓️</span>
      <div className="revocation-callout-body">
        <div className="revocation-callout-title">
          Chain Not Trusted
          {chainValidationError && ` — ${humanError}`}
        </div>
        <div className="revocation-callout-detail">
          The certificate chain could not be validated against the trusted root store.
          This is normal for private/internal certificates using custom CAs.
        </div>
      </div>
    </div>
  );
}

// ─── MAIN COMPONENT ───────────────────────────────────────────────────────────
export function RevocationPanel({ cert, orgId, token, canWrite, toast, onUpdate }) {
  const [toggling, setToggling] = useState(false);

  const handleDeepCheckToggle = async (newVal) => {
    if (toggling) return;
    // Optimistic update
    onUpdate?.({ revocationDeepCheck: newVal });
    setToggling(true);
    try {
      const result = await api.patchCertRevocationDeepCheck(orgId, cert.id, newVal, token);
      // Reconcile with server response
      onUpdate?.({ revocationDeepCheck: result.revocationDeepCheck });
    } catch (e) {
      // Revert optimistic update
      onUpdate?.({ revocationDeepCheck: !newVal });
      toast("Failed to update deep-check setting: " + e.message, "error");
    } finally {
      setToggling(false);
    }
  };

  const revStatus     = cert.revocationStatus;
  const checkedAt     = cert.revocationCheckedAt;
  const source        = cert.revocationSource;
  const reason        = cert.revocationReason;
  const reasonCode    = cert.revocationReasonCode;
  const revokedAt     = cert.revokedAt;
  const deepCheck     = cert.revocationDeepCheck ?? false;
  const chainTrusted  = cert.chainTrusted;
  const chainError    = cert.chainValidationError;

  return (
    <div className="revocation-panel" aria-label="Revocation and Chain Validation">
      {/* Section title */}
      <div className="cert-detail-section-title">Revocation &amp; Chain</div>

      {/* Status callout */}
      <RevocationCallout cert={cert} />

      {/* Chain callout — only if evaluated */}
      {(chainTrusted !== null && chainTrusted !== undefined) && (
        <ChainCallout cert={cert} />
      )}

      {/* Detail fields */}
      <div className="cert-field-grid" style={{ marginTop: "0.75rem" }}>
        <span className="cert-field-key">Revocation Status</span>
        <span className="cert-field-val">
          {REVOCATION_STATUS_LABELS[revStatus] || revStatus || "—"}
        </span>

        {source && (
          <>
            <span className="cert-field-key">Source</span>
            <span className="cert-field-val">{humanizeRevocationSource(source)}</span>
          </>
        )}

        {reason && (
          <>
            <span className="cert-field-key">Reason</span>
            <span className="cert-field-val" style={isHighPriorityReason(reason) ? { color: "#ff4444", fontWeight: 700 } : {}}>
              {humanizeRevocationReason(reason)}
              {reasonCode != null && (
                <span style={{ color: "var(--muted)", fontWeight: 400, marginLeft: 6, fontSize: "0.75rem" }}>
                  (code {reasonCode})
                </span>
              )}
            </span>
          </>
        )}

        {revokedAt && (
          <>
            <span className="cert-field-key">Revoked At</span>
            <span className="cert-field-val">{fmtDate(revokedAt)}</span>
          </>
        )}

        <span className="cert-field-key">Last Checked</span>
        <span className="cert-field-val">
          {checkedAt
            ? <span title={fmtDate(checkedAt)}>{fmtRelative(checkedAt)}</span>
            : <span style={{ color: "var(--muted)" }}>Never</span>}
        </span>

        {chainTrusted !== null && chainTrusted !== undefined && (
          <>
            <span className="cert-field-key">Chain Trusted</span>
            <span className="cert-field-val" style={{ color: chainTrusted ? "var(--green)" : "#d966d6" }}>
              {chainTrusted ? "Yes" : "No"}
            </span>
          </>
        )}

        {chainError && (
          <>
            <span className="cert-field-key">Chain Error</span>
            <span className="cert-field-val" style={{ color: "#cc66cc" }}>
              {humanizeChainError(chainError)}
            </span>
          </>
        )}
      </div>

      {/* Deep-check toggle (FE-3) */}
      <div className="deep-check-row">
        <div className="deep-check-label">
          <div className="deep-check-label-title">Complete revocation check</div>
          <div className="deep-check-label-sub">
            Queries both OCSP and CRL for this certificate (slower, more thorough).
          </div>
        </div>
        {toggling ? (
          <Spinner />
        ) : (
          <NsSwitch
            id={`deep-check-${cert.id}`}
            checked={deepCheck}
            onChange={handleDeepCheckToggle}
            disabled={!canWrite || toggling}
          />
        )}
      </div>
    </div>
  );
}
