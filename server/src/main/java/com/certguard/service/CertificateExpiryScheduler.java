package com.certguard.service;

import com.certguard.entity.CertificateRecord;
import com.certguard.entity.Target;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled job that checks all organisations for certificates nearing expiry
 * and dispatches alerts via NotificationService.
 *
 * Thresholds (configurable via application.yml):
 *   app.alert.warning-days  (default 30) → severity "WARNING"
 *   app.alert.critical-days (default 7)  → severity "CRITICAL"
 *
 * Runs daily at 08:00 server time. Cron configurable via
 *   app.alert.schedule-cron (default "0 0 8 * * *")
 *
 * Deduplication: each CertificateRecord carries a last_alert_sent_at timestamp.
 * A certificate is skipped if it was alerted within the last
 *   app.alert.dedup-hours (default 23) hours.  This prevents alert storms on
 *   long-expired certificates that would otherwise re-fire on every daily run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertificateExpiryScheduler {

    private final OrganizationRepository  organisationRepository;
    private final CertificateRecordRepository certRepository;
    private final NotificationService     notificationService;

    @Value("${app.alert.warning-days:30}")  private int warningDays;
    @Value("${app.alert.critical-days:7}") private int criticalDays;
    @Value("${app.alert.dedup-hours:23}")  private int dedupHours;

    /**
     * Daily sweep across all orgs.
     * Finds certificates expiring within the warning window (or already expired)
     * and dispatches alerts, subject to per-record deduplication.
     */
    @Scheduled(cron = "${app.alert.schedule-cron:0 0 8 * * *}")
    @Transactional
    public void checkExpiringCertificates() {
        log.info("Starting daily certificate expiry notification sweep (dedup window={}h)", dedupHours);

        List<UUID> orgIds = organisationRepository.findAll()
                .stream()
                .map(org -> org.getId())
                .toList();

        Instant now      = Instant.now();
        Instant warnCutoff = now.plus(warningDays, ChronoUnit.DAYS);
        // Dedup gate: skip certs already alerted within the last dedupHours
        Instant dedupCutoff = now.minus(dedupHours, ChronoUnit.HOURS);

        int alertsSent  = 0;
        int alertsSkipped = 0;

        for (UUID orgId : orgIds) {
            // Certs expiring within the warning window (includes already-expired
            // when from < now, because we also pass now.minus(365d)..now below).
            List<CertificateRecord> expiring = certRepository.findExpiringByOrgId(orgId, now, warnCutoff);

            for (CertificateRecord cert : expiring) {
                if (shouldSkip(cert, dedupCutoff)) { alertsSkipped++; continue; }
                Target target = cert.getTarget();
                if (target == null || !Boolean.TRUE.equals(target.getEnabled())) continue;

                long daysLeft = ChronoUnit.DAYS.between(now, cert.getExpiryDate());
                String severity = (daysLeft <= criticalDays) ? "CRITICAL" : "WARNING";

                log.debug("Cert expiry alert — target {}:{} daysLeft={} severity={}",
                        target.getHost(), target.getPort(), daysLeft, severity);

                notificationService.dispatchExpiryAlert(target, (int) daysLeft, severity);
                certRepository.stampAlertSentAt(cert.getId(), now);
                alertsSent++;
            }

            // Already-expired certs (expiry in the past, up to 365 days back)
            List<CertificateRecord> expired = certRepository.findExpiringByOrgId(
                    orgId, now.minus(365, ChronoUnit.DAYS), now);

            for (CertificateRecord cert : expired) {
                if (shouldSkip(cert, dedupCutoff)) { alertsSkipped++; continue; }
                Target target = cert.getTarget();
                if (target == null || !Boolean.TRUE.equals(target.getEnabled())) continue;

                long daysLeft = ChronoUnit.DAYS.between(now, cert.getExpiryDate()); // negative
                notificationService.dispatchExpiryAlert(target, (int) daysLeft, "CRITICAL");
                certRepository.stampAlertSentAt(cert.getId(), now);
                alertsSent++;
            }
        }

        log.info("Certificate expiry sweep complete — {} alert(s) dispatched, {} skipped (dedup)",
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
