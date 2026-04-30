package com.certguard.entity;

import com.certguard.enums.CertStatus;
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
}
