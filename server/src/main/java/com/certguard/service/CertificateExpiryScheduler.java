package com.certguard.service;

import com.certguard.entity.CertificateRecord;
import com.certguard.repository.CertificateRecordRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Daily sweep across all orgs. Finds certificates expiring within the warning
 * window and delegates all per-cert logic (dedup gate, stamping, AFTER_COMMIT
 * dispatch) to {@link ExpiryEvaluationService} (RFC 0008 §2 / §3 / §4).
 *
 * Since warning_days is now configurable per-target/org (RFC 0008 §3), the
 * fetch window is widened to the maximum configured value via
 * {@link ExpiryEvaluationService#resolveMaxWarningDays()} so that no cert
 * with a larger-than-default window is missed. ExpiryEvaluationService then
 * applies the per-cert resolved threshold precisely in memory.
 *
 * This class is a thin scheduler shell; all evaluation logic lives in
 * ExpiryEvaluationService.
 */
@Component
public class CertificateExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(CertificateExpiryScheduler.class);

    private final CertificateRecordRepository certRepository;
    private final ExpiryEvaluationService expiryEvaluationService;

    public CertificateExpiryScheduler(CertificateRecordRepository certRepository,
                                      ExpiryEvaluationService expiryEvaluationService) {
        this.certRepository = certRepository;
        this.expiryEvaluationService = expiryEvaluationService;
    }

    /**
     * Daily sweep across all orgs.
     *
     * Fetch window is widened to max(configured warning_days) so that per-target
     * overrides with a larger window are included in the candidate set (RFC 0008 §3.3).
     * ExpiryEvaluationService filters precisely in memory using the resolved per-cert value.
     */
    @Scheduled(cron = "${app.alert.schedule-cron:0 0 8 * * *}")
    @SchedulerLock(name = "CertificateExpiryScheduler_checkExpiringCertificates",
                   lockAtMostFor = "PT2H", lockAtLeastFor = "PT30M")
    @Transactional
    public void checkExpiringCertificates() {
        log.info("Starting daily certificate expiry notification sweep");

        // Widen the fetch window to max configured warning_days (RFC 0008 §3.3).
        int maxWarningDays = expiryEvaluationService.resolveMaxWarningDays();
        Instant now        = Instant.now();
        Instant warnCutoff = now.plus(maxWarningDays, ChronoUnit.DAYS);

        log.debug("Sweep fetch window: {} days (max across all settings + app default)", maxWarningDays);

        // Single JOIN FETCH query — no per-org round-trips or N+1.
        List<CertificateRecord> expiring = certRepository.findExpiringWithTargets(now, warnCutoff);

        if (expiring.isEmpty()) {
            log.info("Certificate expiry sweep complete — no in-window certs found");
            return;
        }

        // Batch evaluation: settings pre-loaded in two queries; no per-cert DB calls.
        int dispatched = expiryEvaluationService.evaluateAndNotify(
                expiring, ExpiryEvaluationService.EvaluationMode.SCHEDULED);

        log.info("Certificate expiry sweep complete — {} alert(s) enqueued out of {} candidate cert(s)",
                dispatched, expiring.size());
    }
}
