package com.certguard.service;

import com.certguard.entity.CertificateRecord;
import com.certguard.entity.Target;
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
 * Scheduled job that checks all organisations for certificates nearing expiry
 * and dispatches alerts via NotificationService.
 *
 * Thresholds (configurable via application.yml):
 *   app.alert.warning-days  (default 30) -- severity "WARNING"
 *   app.alert.critical-days (default 7)  -- severity "CRITICAL"
 *
 * Runs daily at 08:00 server time. Cron configurable via
 *   app.alert.schedule-cron (default "0 0 8 * * *")
 *
 * Deduplication: each CertificateRecord carries a last_alert_sent_at timestamp.
 * A certificate is skipped if it was alerted within the last
 *   app.alert.dedup-hours (default 23) hours.  This prevents alert storms on
 *   long-expired certificates that would otherwise re-fire on every daily run.
 */
@Component
public class CertificateExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(CertificateExpiryScheduler.class);

    private final CertificateRecordRepository certRepository;
    private final NotificationService notificationService;

    @Value("${app.alert.warning-days:30}")  private int warningDays;
    @Value("${app.alert.critical-days:7}") private int criticalDays;
    @Value("${app.alert.dedup-hours:23}")  private int dedupHours;

    public CertificateExpiryScheduler(CertificateRecordRepository certRepository,
                                      NotificationService notificationService) {
        this.certRepository = certRepository;
        this.notificationService = notificationService;
    }

    /**
     * Daily sweep across all orgs.
     * Finds certificates expiring within the warning window and dispatches alerts,
     * subject to per-record deduplication. Uses a single JOIN FETCH query to avoid
     * the N+1 pattern that arose from the previous per-org loop.
     */
    @Scheduled(cron = "${app.alert.schedule-cron:0 0 8 * * *}")
    @SchedulerLock(name = "CertificateExpiryScheduler_checkExpiringCertificates",
                   lockAtMostFor = "PT2H", lockAtLeastFor = "PT30M")
    @Transactional
    public void checkExpiringCertificates() {
        log.info("Starting daily certificate expiry notification sweep (dedup window={}h)", dedupHours);

        Instant now         = Instant.now();
        Instant warnCutoff  = now.plus(warningDays, ChronoUnit.DAYS);
        // Dedup gate: skip certs already alerted within the last dedupHours
        Instant dedupCutoff = now.minus(dedupHours, ChronoUnit.HOURS);

        int alertsSent    = 0;
        int alertsSkipped = 0;

        // Single query -- fetches certs + targets together, no per-org round-trips.
        List<CertificateRecord> expiring = certRepository.findExpiringWithTargets(now, warnCutoff);

        for (CertificateRecord cert : expiring) {
            if (shouldSkip(cert, dedupCutoff)) { alertsSkipped++; continue; }
            Target target = cert.getTarget();
            // target.enabled is already filtered in the JPQL, but guard against null
            if (target == null) continue;

            long daysLeft = ChronoUnit.DAYS.between(now, cert.getExpiryDate());
            String severity = (daysLeft <= criticalDays) ? "CRITICAL" : "WARNING";

            log.debug("Cert expiry alert -- target {}:{} daysLeft={} severity={}",
                    target.getHost(), target.getPort(), daysLeft, severity);

            boolean dispatched = notificationService.dispatchExpiryAlert(cert, (int) daysLeft, severity);
            if (dispatched) {
                certRepository.stampAlertSentAt(cert.getId(), now);
                alertsSent++;
            }
        }

        log.info("Certificate expiry sweep complete -- {} alert(s) dispatched, {} skipped (dedup)",
                alertsSent, alertsSkipped);
    }

    /**
     * Returns true if an alert was already sent for this cert within the dedup window.
     */
    private boolean shouldSkip(CertificateRecord cert, Instant dedupCutoff) {
        return cert.getLastAlertSentAt() != null
                && cert.getLastAlertSentAt().isAfter(dedupCutoff);
    }
}
