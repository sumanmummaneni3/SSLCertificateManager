package com.certguard.renewal.orchestration;

import com.certguard.renewal.ca.CaIssuedPackage;
import com.certguard.renewal.ca.CaNotConfiguredException;
import com.certguard.renewal.ca.CaProvider;
import com.certguard.renewal.ca.CaProviderRegistry;
import com.certguard.renewal.ca.CaRenewalRequest;
import com.certguard.renewal.client.CoreServiceClient;
import com.certguard.renewal.entity.CertIssuanceHistory;
import com.certguard.renewal.entity.CertificatePackage;
import com.certguard.renewal.entity.CertificateRenewalRequest;
import com.certguard.renewal.exception.RenewalException;
import com.certguard.renewal.repository.CertIssuanceHistoryRepository;
import com.certguard.renewal.repository.RenewalRequestRepository;
import com.certguard.renewal.storage.PackageStore;
import com.certguard.renewal.web.dto.CreateRenewalRequest;
import com.certguard.renewal.web.dto.RenewalResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RenewalOrchestrator {

    private static final List<String> ACTIVE_STATES = List.of(
            "REQUESTED", "CSR_PENDING", "CSR_RECEIVED",
            "CA_PENDING", "CA_ISSUED", "STORED", "DELIVERY_QUEUED");

    private final RenewalRequestRepository renewalRepository;
    private final CertIssuanceHistoryRepository issuanceHistoryRepository;
    private final PackageStore packageStore;
    private final CaProviderRegistry caProviderRegistry;
    private final CoreServiceClient coreServiceClient;

    @Transactional
    public RenewalResponse createRenewal(CreateRenewalRequest req) {
        if (renewalRepository.existsByCertIdAndStatusIn(req.certId(), ACTIVE_STATES)) {
            throw new RenewalException("A renewal is already in progress for certificate: " + req.certId());
        }

        CertificateRenewalRequest renewal = CertificateRenewalRequest.builder()
                .orgId(req.orgId())
                .certId(req.certId())
                .targetId(req.targetId())
                .agentId(req.agentId())
                .status("REQUESTED")
                .caProvider(req.caProvider() != null && !req.caProvider().isBlank()
                        ? req.caProvider().toUpperCase() : "NOOP")
                .requestedBy(req.requestedBy())
                .targetInstallPath(req.targetInstallPath())
                .build();

        renewal = renewalRepository.save(renewal);

        UUID csrJobId = coreServiceClient.enqueueCsrJob(
                renewal.getId(), req.agentId(), req.orgId(), req.targetId(),
                req.commonName(), req.sans());

        renewal.setCsrJobId(csrJobId);
        renewal.setStatus("CSR_PENDING");
        renewal = renewalRepository.save(renewal);

        log.info("Renewal created — renewalId: {}, certId: {}, agentId: {}",
                renewal.getId(), req.certId(), req.agentId());

        return RenewalResponse.from(renewal);
    }

    @Transactional
    public void submitCsr(UUID renewalId, String csrPem) {
        CertificateRenewalRequest renewal = renewalRepository.findById(renewalId)
                .orElseThrow(() -> new RenewalException("Renewal not found: " + renewalId));

        if (!"CSR_PENDING".equals(renewal.getStatus())) {
            throw new RenewalException("Renewal is not in CSR_PENDING state: " + renewalId);
        }

        renewal.setCsrPem(csrPem);
        renewal.setStatus("CSR_RECEIVED");
        renewalRepository.save(renewal);

        log.info("CSR received for renewalId: {} — triggering async CA call", renewalId);
        driveCaAsync(renewalId);
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void driveCaAsync(UUID renewalId) {
        CertificateRenewalRequest renewal = renewalRepository.findById(renewalId)
                .orElseThrow(() -> new RenewalException("Renewal not found: " + renewalId));

        renewal.setStatus("CA_PENDING");
        renewalRepository.save(renewal);

        try {
            CaProvider provider = caProviderRegistry.resolve(
                    renewal.getCaProvider(), null, renewal.getOrgId());

            CaRenewalRequest caReq = new CaRenewalRequest(
                    renewal.getCsrPem(), "", List.of(), renewal.getOrgId(), 365);

            CaIssuedPackage issued = provider.requestCertificate(caReq);
            renewal.setCaExternalRef(issued.externalRef());
            renewal.setStatus("CA_ISSUED");

            CertificatePackage pkg = packageStore.store(renewal.getOrgId(), renewal.getId(), issued);
            renewal.setPackageId(pkg.getId());
            renewal.setStatus("STORED");

            UUID deliveryJobId = coreServiceClient.enqueueCertDelivery(
                    renewal.getId(), renewal.getAgentId(), renewal.getOrgId(),
                    renewal.getTargetId(), renewal.getTargetInstallPath(),
                    pkg.getId(), pkg.getChecksumSha256(), pkg.getFileName());

            renewal.setDeliveryJobId(deliveryJobId);
            renewal.setStatus("DELIVERY_QUEUED");
            renewalRepository.save(renewal);

            recordIssuanceHistory(renewal, issued);

            coreServiceClient.triggerNotification(
                    "RENEWAL_READY", renewal.getId(), renewal.getOrgId(),
                    renewal.getRequestedBy(), renewal.getTargetInstallPath(),
                    pkg.getFileName(), pkg.getChecksumSha256(), null);

            log.info("CA issued cert for renewalId: {} — delivery job {} enqueued",
                    renewalId, deliveryJobId);

        } catch (CaNotConfiguredException e) {
            log.warn("CA not configured for renewalId: {} — {}", renewalId, e.getMessage());
            failRenewal(renewal, "CA integration not configured: " + e.getMessage());

        } catch (Exception e) {
            log.error("CA call failed for renewalId: {}", renewalId, e);
            failRenewal(renewal, "CA call failed: " + e.getMessage());
        }
    }

    @Transactional
    public RenewalResponse cancelRenewal(UUID orgId, UUID renewalId) {
        CertificateRenewalRequest renewal = renewalRepository.findByIdAndOrgId(renewalId, orgId)
                .orElseThrow(() -> new RenewalException("Renewal not found: " + renewalId));

        if (List.of("DELIVERED", "FAILED", "CANCELLED").contains(renewal.getStatus())) {
            throw new RenewalException("Cannot cancel a renewal in terminal state: " + renewal.getStatus());
        }

        renewal.setStatus("CANCELLED");
        renewalRepository.save(renewal);
        log.info("Renewal cancelled — renewalId: {}", renewalId);
        return RenewalResponse.from(renewal);
    }

    @Transactional
    public void onDeliveryCompleted(UUID renewalId) {
        renewalRepository.findById(renewalId).ifPresent(renewal -> {
            renewal.setStatus("DELIVERED");
            renewalRepository.save(renewal);

            coreServiceClient.triggerNotification(
                    "RENEWAL_INSTALLED", renewal.getId(), renewal.getOrgId(),
                    renewal.getRequestedBy(), renewal.getTargetInstallPath(),
                    null, null, null);

            log.info("Renewal marked DELIVERED — renewalId: {}", renewalId);
        });
    }

    @Transactional
    public void onDeliveryFailed(UUID renewalId, String errorDetail) {
        renewalRepository.findById(renewalId).ifPresent(renewal -> {
            renewal.setStatus("FAILED");
            renewal.setFailureReason(errorDetail);
            renewalRepository.save(renewal);

            coreServiceClient.triggerNotification(
                    "RENEWAL_FAILED", renewal.getId(), renewal.getOrgId(),
                    renewal.getRequestedBy(), renewal.getTargetInstallPath(),
                    null, null, errorDetail);

            log.warn("Renewal marked FAILED — renewalId: {}, reason: {}", renewalId, errorDetail);
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void failRenewal(CertificateRenewalRequest renewal, String reason) {
        renewal.setStatus("FAILED");
        renewal.setFailureReason(reason);
        renewalRepository.save(renewal);

        coreServiceClient.triggerNotification(
                "RENEWAL_FAILED", renewal.getId(), renewal.getOrgId(),
                renewal.getRequestedBy(), null, null, null, reason);
    }

    private void recordIssuanceHistory(CertificateRenewalRequest renewal, CaIssuedPackage issued) {
        try {
            CertIssuanceHistory history = CertIssuanceHistory.builder()
                    .orgId(renewal.getOrgId())
                    .certId(renewal.getCertId())
                    .targetId(renewal.getTargetId())
                    .renewalId(renewal.getId())
                    .caProvider(renewal.getCaProvider())
                    .expiresAt(issued.expiresAt())
                    .serialNumber(issued.serialNumber())
                    .issuerDn(issued.issuerDn())
                    .build();
            issuanceHistoryRepository.save(history);
        } catch (Exception e) {
            log.warn("Failed to record issuance history for renewalId: {}", renewal.getId(), e);
        }
    }
}
