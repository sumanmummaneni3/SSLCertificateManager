package com.certguard.renewal.web.dto;

import com.certguard.renewal.entity.CertificateRenewalRequest;

import java.time.Instant;
import java.util.UUID;

public record RenewalResponse(
        UUID id,
        UUID certId,
        UUID targetId,
        UUID agentId,
        String status,
        String caProvider,
        String targetInstallPath,
        UUID packageId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static RenewalResponse from(CertificateRenewalRequest r) {
        return new RenewalResponse(
                r.getId(), r.getCertId(), r.getTargetId(), r.getAgentId(),
                r.getStatus(), r.getCaProvider(), r.getTargetInstallPath(),
                r.getPackageId(), r.getFailureReason(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
