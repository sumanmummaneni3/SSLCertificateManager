package com.certguard.renewal.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cert_issuance_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CertIssuanceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "cert_id", nullable = false)
    private UUID certId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "renewal_id")
    private UUID renewalId;

    @Column(name = "ca_provider", nullable = false, length = 32)
    private String caProvider;

    @Column(name = "common_name", length = 256)
    private String commonName;

    @Column(name = "issued_at", nullable = false)
    @Builder.Default
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "serial_number", length = 128)
    private String serialNumber;

    @Column(name = "issuer_dn", columnDefinition = "TEXT")
    private String issuerDn;
}
