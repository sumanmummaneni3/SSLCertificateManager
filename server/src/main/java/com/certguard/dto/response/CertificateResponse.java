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

    /** GOOD | REVOKED | UNKNOWN | UNCHECKED | null (not yet evaluated). */
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
