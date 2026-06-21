package com.certguard.service;

import com.certguard.dto.request.RevocationDeepCheckRequest;
import com.certguard.dto.response.CertificateResponse;
import com.certguard.dto.response.DashboardResponse;
import com.certguard.dto.response.RevocationDeepCheckResponse;
import com.certguard.dto.response.TargetResponse;
import com.certguard.entity.CertificateRecord;
import com.certguard.entity.Target;
import com.certguard.enums.CertStatus;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.repository.TargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CertificateService {

    private final CertificateRecordRepository certRepository;
    private final TargetRepository targetRepository;

    @Value("${app.alert.warning-days:30}")
    private int warningDays;

    @Value("${app.alert.critical-days:7}")
    private int criticalDays;

    public Page<CertificateResponse> listCertificates(UUID orgId, Pageable pageable) {
        return certRepository.findAllByOrgId(orgId, pageable).map(this::toResponse);
    }

    /**
     * Single certificate with its target embedded (including agent assignment), so the
     * UI detail page can decide whether the target is agent-managed and offer renewal.
     */
    public CertificateResponse getCertificate(UUID orgId, UUID certId) {
        CertificateRecord cert = certRepository.findByIdAndOrgId(certId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate not found"));
        CertificateResponse resp = toResponse(cert);
        resp.setTarget(toTargetResponse(cert.getTarget()));
        return resp;
    }

    private TargetResponse toTargetResponse(Target target) {
        if (target == null) return null;
        return TargetResponse.builder()
                .id(target.getId())
                .host(target.getHost())
                .port(target.getPort())
                .hostType(target.getHostType())
                .isPrivate(target.getIsPrivate())
                .agentId(target.getAgent() != null ? target.getAgent().getId() : null)
                .agentName(target.getAgent() != null ? target.getAgent().getName() : null)
                .build();
    }

    public List<CertificateResponse> getExpiring(UUID orgId, int days) {
        Instant threshold = Instant.now().plus(days, ChronoUnit.DAYS);
        return certRepository.findExpiringByOrgId(orgId, Instant.now(), threshold)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public DashboardResponse getDashboard(UUID orgId) {
        long totalTargets  = targetRepository.countByOrganizationId(orgId);
        long valid         = certRepository.countByOrgIdAndStatus(orgId, CertStatus.VALID);
        long expiring      = certRepository.countByOrgIdAndStatus(orgId, CertStatus.EXPIRING);
        long expired       = certRepository.countByOrgIdAndStatus(orgId, CertStatus.EXPIRED);
        long unreachable   = certRepository.countByOrgIdAndStatus(orgId, CertStatus.UNREACHABLE);
        long revoked       = certRepository.countByOrgIdAndStatus(orgId, CertStatus.REVOKED);
        long invalid       = certRepository.countByOrgIdAndStatus(orgId, CertStatus.INVALID);

        return DashboardResponse.builder()
                .totalTargets(totalTargets).valid(valid)
                .expiring(expiring).expired(expired).unreachable(unreachable)
                .revoked(revoked).invalid(invalid)
                .build();
    }

    /**
     * Persists the per-cert deep-check toggle (RFC 0009 §10.2 / BE-12).
     *
     * @throws ResourceNotFoundException when certId is not found under orgId
     */
    @Transactional
    public RevocationDeepCheckResponse updateRevocationDeepCheck(UUID orgId, UUID certId,
                                                                  RevocationDeepCheckRequest req) {
        int updated = certRepository.updateRevocationDeepCheck(certId, orgId, req.getEnabled());
        if (updated == 0) {
            throw new ResourceNotFoundException("Certificate not found: " + certId);
        }
        return RevocationDeepCheckResponse.builder()
                .id(certId)
                .revocationDeepCheck(req.getEnabled())
                .build();
    }

    private CertificateResponse toResponse(CertificateRecord cert) {
        long days = ChronoUnit.DAYS.between(Instant.now(), cert.getExpiryDate());

        // revocationStatus: normalize null → "UNCHECKED" so the response never emits null.
        // Distinction: UNCHECKED = never attempted; UNKNOWN = attempted but inconclusive.
        String revocationStatusStr = cert.getRevocationStatus() != null
                ? cert.getRevocationStatus().name()
                : "UNCHECKED";

        return CertificateResponse.builder()
                .id(cert.getId())
                .orgId(cert.getOrgId())           // owning org — needed for MSP/impersonation
                .targetId(cert.getTarget().getId())
                .host(cert.getTarget().getHost())
                .port(cert.getTarget().getPort())
                .commonName(cert.getCommonName())
                .issuer(cert.getIssuer())
                .serialNumber(cert.getSerialNumber())
                .expiryDate(cert.getExpiryDate())
                .notBefore(cert.getNotBefore())
                .daysRemaining(days)
                .status(cert.getStatus())
                .scannedAt(cert.getScannedAt())
                .scannedByAgentId(cert.getScannedByAgent() != null ? cert.getScannedByAgent().getId() : null)
                // RFC 0009: revocation fields
                .revocationStatus(revocationStatusStr)
                .revocationSource(cert.getRevocationSource() != null
                        ? cert.getRevocationSource().name() : null)
                .revocationReason(cert.getRevocationReason())
                .revocationReasonCode(cert.getRevocationReasonCode())
                .revokedAt(cert.getRevokedAt())
                .revocationCheckedAt(cert.getRevocationCheckedAt())
                .revocationDeepCheck(cert.isRevocationDeepCheck())
                // RFC 0009: chain fields
                .chainTrusted(cert.getChainTrusted())
                .chainValidationError(cert.getChainValidationError() != null
                        ? cert.getChainValidationError().name() : null)
                // Derived convenience
                .onHold(cert.isOnHold())
                .build();
    }
}
