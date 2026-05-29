package com.certguard.renewal.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "certificate_renewal_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CertificateRenewalRequest extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "cert_id", nullable = false)
    private UUID certId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(nullable = false, length = 32)
    @Builder.Default
    private String status = "REQUESTED";

    @Column(name = "ca_provider", nullable = false, length = 32)
    @Builder.Default
    private String caProvider = "NOOP";

    @Column(name = "ca_external_ref", length = 256)
    private String caExternalRef;

    @Column(name = "csr_pem", columnDefinition = "TEXT")
    private String csrPem;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "target_install_path", length = 1024)
    private String targetInstallPath;

    @Column(name = "package_id")
    private UUID packageId;

    @Column(name = "csr_job_id")
    private UUID csrJobId;

    @Column(name = "delivery_job_id")
    private UUID deliveryJobId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;
}
