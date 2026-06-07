package com.certguard.service;

import com.certguard.entity.CertificateRecord;
import com.certguard.repository.CertificateRecordRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Daily sweep across all orgs. Finds certificates expiring within the warning
 * window and delegates all per-cert logic (dedup gate, stamping, AFTER_COMMIT
 * dispatch) to {@link ExpiryEvaluationService} (RFC 0008 §2 / §4).
 *
 * Thresholds (configurable via application.yml):
 *   app.alert.warning-days  (default 30) -- fetch window for the cross-org query
 *   app.alert.schedule-cron (default "0 0 8 * * *")
 *
 * This class is now a thin scheduler shell; all evaluation logic lives in
 * ExpiryEvaluationService.
 */
@Component
public class CertificateExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(CertificateExpiryScheduler.class);

    private final CertificateRecordRepository certRepository;
    private final ExpiryEvaluationService expiryEvaluationService;

    @Value("${app.alert.warning-days:30}") private int warningDays;

    public CertificateExpiryScheduler(CertificateRecordRepository certRepository,
                                      ExpiryEvaluationService expiryEvaluationService) {
        this.certRepository = certRepository;
        this.expiryEvaluationService = expiryEvaluationService;
    }

    /**
     * Daily sweep across all orgs.
     * Fetches certs + targets in one JOIN FETCH query, then delegates all per-cert
     * logic (dedup gate, stamp, AFTER_COMMIT dispatch) to ExpiryEvaluationService.
     */
    @Scheduled(cron = "${app.alert.schedule-cron:0 0 8 * * *}")
    @SchedulerLock(name = "CertificateExpiryScheduler_checkExpiringCertificates",
                   lockAtMostFor = "PT2H", lockAtLeastFor = "PT30M")
    @Transactional
    public void checkExpiringCertificates() {
        log.info("Starting daily certificate expiry notification sweep");

        Instant now        = Instant.now();
        Instant warnCutoff = now.plus(warningDays, ChronoUnit.DAYS);

        // Single JOIN FETCH query — no per-org round-trips or N+1.
        List<CertificateRecord> expiring = certRepository.findExpiringWithTargets(now, warnCutoff);

        if (expiring.isEmpty()) {
            log.info("Certificate expiry sweep complete — no in-window certs found");
            return;
        }

        int dispatched = expiryEvaluationService.evaluateAndNotify(
                expiring, ExpiryEvaluationService.EvaluationMode.SCHEDULED);

        log.info("Certificate expiry sweep complete — {} alert(s) enqueued out of {} in-window cert(s)",
                dispatched, expiring.size());
    }
}
