package com.certguard.service;

import com.certguard.entity.CertificateRecord;
import com.certguard.enums.CertStatus;
import com.certguard.enums.RevocationStatus;
import com.certguard.event.CertRevocationTransitionEvent;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.service.chain.ChainValidationResult;
import com.certguard.service.revocation.RevocationCheckService;
import com.certguard.service.revocation.RevocationResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Set;

/**
 * Daily re-check of revocation status for all non-expired certs (RFC 0009 §3.7).
 *
 * <p>This is the key mechanism for detecting between-scan revocations — a cert can
 * be revoked by its CA without the serial number changing, so only a periodic
 * re-query of OCSP/CRL (not a full TLS rescan) will detect it.
 *
 * <p>Mirrors the ShedLock pattern from {@link CertificateExpiryScheduler}.
 */
@Component
public class RevocationRecheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(RevocationRecheckScheduler.class);

    private static final Set<CertStatus> EXCLUDED_STATUSES =
            Set.of(CertStatus.EXPIRED, CertStatus.UNREACHABLE);

    private final CertificateRecordRepository certRepository;
    private final RevocationCheckService revocationCheckService;
    private final ExpiryEvaluationService expiryEvaluationService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.revocation.recheck.schedule-cron:0 0 4 * * *}")
    private String scheduleCron; // read by @Scheduled below via SpEL

    @Value("${app.revocation.recheck.min-age-hours:20}")
    private int minAgeHours;

    @Value("${app.revocation.recheck.batch-size:500}")
    private int batchSize;

    @Value("${app.revocation.shadow:true}")
    private boolean shadowMode;

    @Value("${app.revocation.enabled:true}")
    private boolean revocationEnabled;

    public RevocationRecheckScheduler(CertificateRecordRepository certRepository,
                                       RevocationCheckService revocationCheckService,
                                       ExpiryEvaluationService expiryEvaluationService,
                                       ApplicationEventPublisher eventPublisher) {
        this.certRepository = certRepository;
        this.revocationCheckService = revocationCheckService;
        this.expiryEvaluationService = expiryEvaluationService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Daily 04:00 revocation recheck sweep. ShedLock prevents multi-instance races.
     */
    @Scheduled(cron = "${app.revocation.recheck.schedule-cron:0 0 4 * * *}")
    @SchedulerLock(name = "RevocationRecheckScheduler_recheck",
                   lockAtMostFor = "PT1H", lockAtLeastFor = "PT10M")
    @Transactional
    public void recheckRevocations() {
        if (!revocationEnabled) {
            log.info("Revocation recheck skipped — app.revocation.enabled=false");
            return;
        }

        log.info("Starting daily revocation recheck sweep (shadow={})", shadowMode);

        Instant cutoff = Instant.now().minus(minAgeHours, ChronoUnit.HOURS);
        int pageNum = 0;
        int totalChecked = 0;
        int transitioned = 0;

        Page<CertificateRecord> page;
        do {
            page = certRepository.findEligibleForRevocationRecheck(
                    EXCLUDED_STATUSES, cutoff,
                    PageRequest.of(pageNum, batchSize));

            for (CertificateRecord cert : page.getContent()) {
                try {
                    boolean changed = recheckSingle(cert);
                    if (changed) transitioned++;
                    totalChecked++;
                } catch (Exception e) {
                    log.warn("Revocation recheck failed for cert {}: {}", cert.getId(), e.getMessage());
                }
            }
            pageNum++;
        } while (page.hasNext());

        log.info("Revocation recheck complete — {} certs checked, {} status transitions",
                totalChecked, transitioned);
    }

    /**
     * Rechecks a single cert and persists the result.
     *
     * @return true if the CertStatus changed (for metrics/logging)
     */
    boolean recheckSingle(CertificateRecord cert) {
        X509Certificate[] chain = decodeCert(cert.getPublicCertB64());
        if (chain.length == 0) {
            log.debug("Skipping cert {} — could not decode publicCertB64", cert.getId());
            return false;
        }

        RevocationStatus previousRevStatus = cert.getRevocationStatus();
        CertStatus previousStatus = cert.getStatus();

        RevocationResult revResult = revocationCheckService.check(
                chain, null /* no staple for stored certs */, cert.isRevocationDeepCheck());

        // Reconstruct stored chain result (chain didn't change on recheck).
        ChainValidationResult storedChain = buildStoredChainResult(cert);

        // Persist revocation fields (always record, even in shadow mode).
        cert.setRevocationStatus(revResult.status());
        cert.setRevocationSource(revResult.source());
        cert.setRevocationCheckedAt(revResult.checkedAt());
        cert.setRevocationReason(revResult.reason());
        cert.setRevocationReasonCode(revResult.reasonCode());
        cert.setRevokedAt(revResult.revokedAt());

        // Determine new status.
        CertStatus newStatus = expiryEvaluationService.determineCertStatus(
                cert.getExpiryDate(), revResult, storedChain,
                cert.getTarget(), cert.getOrgId());

        boolean statusChanged = previousStatus != newStatus;

        if (!shadowMode) {
            cert.setStatus(newStatus);
        } else if (newStatus == CertStatus.REVOKED || newStatus == CertStatus.INVALID) {
            log.info("[SHADOW] Recheck would set status={} for cert {} (was={})",
                    newStatus, cert.getId(), previousStatus);
        }

        certRepository.save(cert);

        // Emit transition event when revocation status changed.
        boolean revStatusChanged = previousRevStatus != revResult.status();
        if (revStatusChanged) {
            eventPublisher.publishEvent(new CertRevocationTransitionEvent(
                    cert.getId(), cert.getOrgId(),
                    previousStatus, cert.getStatus(),
                    previousRevStatus, revResult.status(),
                    revResult.reason(), revResult.source(),
                    Instant.now(), cert.isRevocationDeepCheck()));
        }

        return statusChanged;
    }

    private X509Certificate[] decodeCert(String b64) {
        if (b64 == null || b64.isBlank()) return new X509Certificate[0];
        try {
            byte[] der = Base64.getDecoder().decode(b64);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
            return new X509Certificate[]{cert};
        } catch (Exception e) {
            log.warn("Failed to decode certificate: {}", e.getMessage());
            return new X509Certificate[0];
        }
    }

    private ChainValidationResult buildStoredChainResult(CertificateRecord cert) {
        int depth = cert.getChainDepth() != null ? cert.getChainDepth() : 1;
        if (Boolean.TRUE.equals(cert.getChainTrusted())) {
            return ChainValidationResult.trusted(depth);
        }
        if (Boolean.FALSE.equals(cert.getChainTrusted()) && cert.getChainValidationError() != null) {
            return ChainValidationResult.failed(cert.getChainValidationError(), depth);
        }
        // Unknown/null — treat as not yet evaluated (no chain impact on status).
        return ChainValidationResult.trusted(depth);
    }
}
