package com.certguard.service;

import com.certguard.dto.response.CertificateResponse;
import com.certguard.dto.response.DashboardResponse;
import com.certguard.entity.CertificateRecord;
import com.certguard.enums.CertStatus;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.repository.TargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
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

        return DashboardResponse.builder()
                .totalTargets(totalTargets).valid(valid)
                .expiring(expiring).expired(expired).unreachable(unreachable)
                .build();
    }

    private CertificateResponse toResponse(CertificateRecord cert) {
        long days = ChronoUnit.DAYS.between(Instant.now(), cert.getExpiryDate());
        return CertificateResponse.builder()
                .id(cert.getId())
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
                .build();
    }
}
