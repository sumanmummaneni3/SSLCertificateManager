package com.certguard.entity;

import com.certguard.enums.CertStatus;
import com.certguard.enums.ChainValidationError;
import com.certguard.enums.RevocationSource;
import com.certguard.enums.RevocationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity @Table(name = "certificate_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CertificateRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id", nullable = false)
    private Target target;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "common_name", nullable = false)
    private String commonName;

    @Column(nullable = false)
    private String issuer;

    @Column(name = "serial_number", nullable = false)
    private String serialNumber;

    @Column(name = "expiry_date", nullable = false)
    private Instant expiryDate;

    @Column(name = "not_before", nullable = false)
    private Instant notBefore;

    @Column(name = "public_cert_b64", columnDefinition = "TEXT")
    private String publicCertB64;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "cert_status")
    @Builder.Default
    private CertStatus status = CertStatus.VALID;

    @Column(name = "client_org_name")
    private String clientOrgName;

    @Column(name = "division_name")
    private String divisionName;

    @Column(name = "key_algorithm", length = 20)
    private String keyAlgorithm;

    @Column(name = "key_size")
    private Integer keySize;

    @Column(name = "signature_algorithm", length = 50)
    private String signatureAlgorithm;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "subject_alt_names", columnDefinition = "jsonb")
    private List<String> subjectAltNames;

    @Column(name = "chain_depth")
    private Integer chainDepth;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scanned_by_agent_id")
    private Agent scannedByAgent;

    @Column(name = "scanned_at", nullable = false)
    @Builder.Default
    private Instant scannedAt = Instant.now();

    /**
     * UTC timestamp of the most recent expiry alert dispatched for this record.
     * Null means no alert has been sent yet.  Updated by CertificateExpiryScheduler
     * after each successful dispatch to prevent alert storms on already-expired certs.
     */
    @Column(name = "last_alert_sent_at")
    private Instant lastAlertSentAt;

    // ── RFC 0009: Revocation fields ────────────────────────────────────────────

    /** Last revocation check outcome: GOOD, REVOKED, UNKNOWN, UNCHECKED. Stored as text. */
    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_status")
    private RevocationStatus revocationStatus;

    /** Revocation data source: OCSP_STAPLED, OCSP, CRL, NONE. Stored as text. */
    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_source")
    private RevocationSource revocationSource;

    /**
     * Human-readable revocation reason (e.g. "KEY_COMPROMISE"). Stored as text.
     * Null unless revocation_status = REVOKED.
     */
    @Column(name = "revocation_reason")
    private String revocationReason;

    /**
     * Raw RFC 5280 CRLReason code (0–10). Null unless revocation_status = REVOKED.
     * Code 6 = certificateHold (reversible / on-hold), code 8 = removeFromCRL.
     */
    @Column(name = "revocation_reason_code")
    private Integer revocationReasonCode;

    /** Date/time the CA revoked this cert (from OCSP or CRL). Null unless REVOKED. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Timestamp of the most recent completed revocation check (any outcome). */
    @Column(name = "revocation_checked_at")
    private Instant revocationCheckedAt;

    /**
     * Timestamp of the most recent REVOKED alert dispatched for this record.
     * Mirrors last_alert_sent_at; used for transition-gated alerting (fire once per edge).
     */
    @Column(name = "last_revocation_alert_sent_at")
    private Instant lastRevocationAlertSentAt;

    // ── RFC 0009: Chain validation fields ─────────────────────────────────────

    /**
     * Whether the full chain was validated to a trusted anchor.
     * Null = not yet evaluated. False = untrusted/invalid chain.
     */
    @Column(name = "chain_trusted")
    private Boolean chainTrusted;

    /**
     * Structured chain-validation failure reason (§5.1 code). Stored as text.
     * Null when chain_trusted = true.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "chain_validation_error")
    private ChainValidationError chainValidationError;

    /**
     * Per-cert deep-check toggle (RFC 0009 §10.2).
     * When true, RevocationCheckService queries BOTH OCSP and CRL (stop-at-first otherwise).
     */
    @Column(name = "revocation_deep_check", nullable = false)
    @Builder.Default
    private boolean revocationDeepCheck = false;

    // ── Derived convenience field ──────────────────────────────────────────────

    /**
     * Server-derived: true iff this cert is revoked with certificateHold (code 6),
     * indicating a reversible/on-hold state rather than terminal revocation.
     * Computed at read time; not persisted.
     */
    @Transient
    public boolean isOnHold() {
        return RevocationStatus.REVOKED == revocationStatus
                && revocationReasonCode != null
                && revocationReasonCode == 6;
    }
}
