package com.certguard.dto.response;

import com.certguard.enums.CertStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Certificate response DTO.
 *
 * <p>RFC 0009 §API contract: new revocation + chain fields added.
 * {@code onHold} is a server-derived boolean (true iff revocationReasonCode == 6)
 * so the frontend doesn't need to reimplement the rule.
 */
@Data @Builder
public class CertificateResponse {
    private UUID id;
    private UUID targetId;
    /**
     * Owning organization UUID. Included so the UI can construct org-scoped
     * URLs (e.g. the PATCH revocation-deep-check endpoint) without relying on
     * a separate org-context variable — important for MSP/impersonation flows
     * where the cert may belong to a client org, not the acting MSP org.
     */
    private UUID orgId;
    private String host;
    private int port;
    private String commonName;
    private String issuer;
    private String serialNumber;
    private Instant notBefore;
    private Instant expiryDate;
    private long daysRemaining;
    private CertStatus status;
    private String keyAlgorithm;
    private Integer keySize;
    private String signatureAlgorithm;
    private List<String> subjectAltNames;
    private Integer chainDepth;
    private Instant scannedAt;
    private UUID scannedByAgentId;
    /** Populated on the single-certificate detail endpoint so the UI can tell whether
     *  the target is agent-managed (target.agentId) and offer renewal. Null on list views. */
    private TargetResponse target;

    // ── RFC 0009: Revocation fields ────────────────────────────────────────────

    /**
     * GOOD | REVOKED | UNKNOWN | UNCHECKED — never null in the response.
     *
     * <p>Mapping:
     * <ul>
     *   <li>{@code UNCHECKED} — no revocation check has been attempted yet (entity field is null or UNCHECKED).
     *   <li>{@code UNKNOWN}   — check was attempted but result was indeterminate (responder down, stale CRL, etc.).
     *   <li>{@code GOOD}      — definitively good (OCSP or CRL confirms not revoked).
     *   <li>{@code REVOKED}   — definitively revoked (signature-verified response; only when shadow=false).
     * </ul>
     * The frontend should treat UNCHECKED and UNKNOWN as "not confirmed" with neutral copy.
     * The distinction is whether a check was ever attempted (UNCHECKED = never; UNKNOWN = tried and inconclusive).
     */
    private String revocationStatus;
    /** OCSP_STAPLED | OCSP | CRL | NONE | null. */
    private String revocationSource;
    /** Mapped reason string (e.g. "KEY_COMPROMISE"); null unless revoked. */
    private String revocationReason;
    /** Raw RFC 5280 CRLReason code 0..10; null unless revoked. */
    private Integer revocationReasonCode;
    /** Timestamp from CA response; null unless revoked. */
    private Instant revokedAt;
    /** Timestamp of last revocation check. */
    private Instant revocationCheckedAt;
    /** Per-cert deep-check toggle (queries BOTH OCSP and CRL when true). */
    private boolean revocationDeepCheck;

    // ── RFC 0009: Chain fields ─────────────────────────────────────────────────

    /** true = chain trusted; false = untrusted/invalid; null = not yet evaluated. */
    private Boolean chainTrusted;
    /** Structured §5.1 error code; null when trusted. */
    private String chainValidationError;

    /**
     * Server-derived convenience: true iff revocationStatus==REVOKED and reasonCode==6
     * (certificateHold — reversible / on-hold, distinct from terminal revocation).
     */
    private boolean onHold;
}
