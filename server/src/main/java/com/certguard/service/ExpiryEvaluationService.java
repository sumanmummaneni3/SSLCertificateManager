package com.certguard.service;

import com.certguard.entity.CertificateRecord;
import com.certguard.entity.Target;
import com.certguard.repository.CertificateRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;

/**
 * Single convergence point for all certificate expiry evaluation and notification
 * dispatch (RFC 0008 §2).
 *
 * Responsibilities:
 *  - Resolve effective settings (warning/critical thresholds, dedup window).
 *  - Determine whether a cert is in-window for alerting.
 *  - Apply the per-cert dedup gate in SCHEDULED mode (bypassed in FORCE mode).
 *  - Stamp {@code last_alert_sent_at} synchronously in the caller's transaction
 *    (both SCHEDULED and FORCE), so the stamp is durable whether or not the
 *    subsequent email delivery succeeds.
 *  - Enqueue the actual email dispatch as a fire-and-forget AFTER_COMMIT action,
 *    so SMTP I/O never rolls back the calling transaction.
 *
 * FORCE-scan debounce: a FORCE evaluation is suppressed when the target's
 * {@code previousLastScannedAt} (the value before this scan overwrote it) is
 * more recent than {@code app.alert.force-scan-debounce-seconds}. Callers
 * capture that value and pass it in.
 */
@Service
@Transactional(readOnly = true)
public class ExpiryEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryEvaluationService.class);

    public enum EvaluationMode { SCHEDULED, FORCE }

    /**
     * Immutable resolved settings for a single evaluation. The resolution chain
     * (per-target → org-default → app-yml fallback) is the seam for RFC 0008 §3.
     */
    record Settings(boolean enabled, int warningDays, int criticalDays, int dedupHours) {}

    private final NotificationService notificationService;
    private final CertificateRecordRepository certRepository;

    // App-yml fallback thresholds — used until notification_settings table (RFC 0008 §3).
    @Value("${app.alert.warning-days:30}")  private int warningDays;
    @Value("${app.alert.critical-days:7}") private int criticalDays;
    @Value("${app.alert.dedup-hours:23}")  private int dedupHours;
    @Value("${app.alert.force-scan-debounce-seconds:120}") private int forceScanDebounceSeconds;

    public ExpiryEvaluationService(NotificationService notificationService,
                                   CertificateRecordRepository certRepository) {
        this.notificationService = notificationService;
        this.certRepository = certRepository;
    }

    /**
     * Batch form for the daily expiry sweep. Returns the number of alerts dispatched.
     * Each cert is evaluated independently; SCHEDULED mode dedup applies per cert.
     *
     * @param certs certs returned by {@code findExpiringWithTargets}
     * @param mode  evaluation mode (always SCHEDULED from the daily sweep)
     */
    @Transactional
    public int evaluateAndNotify(Collection<CertificateRecord> certs, EvaluationMode mode) {
        int dispatched = 0;
        for (CertificateRecord cert : certs) {
            if (evaluateSingle(cert, mode, null)) dispatched++;
        }
        return dispatched;
    }

    /**
     * Single-cert form. Called from both scan write-paths after the cert row is saved.
     *
     * @param cert                   the saved certificate record
     * @param mode                   SCHEDULED or FORCE
     * @param previousLastScannedAt  the target's {@code lastScannedAt} captured BEFORE the
     *                               current scan overwrote it; used for FORCE debounce.
     *                               May be null (treated as "never scanned" → debounce not active).
     */
    @Transactional
    public void evaluateAndNotify(CertificateRecord cert, EvaluationMode mode, Instant previousLastScannedAt) {
        evaluateSingle(cert, mode, previousLastScannedAt);
    }

    // ── Core algorithm (RFC 0008 §2.3) ───────────────────────────────────────

    /**
     * Evaluates a single certificate and, if appropriate, stamps + enqueues dispatch.
     *
     * @return true if an alert was enqueued for dispatch
     */
    private boolean evaluateSingle(CertificateRecord cert, EvaluationMode mode,
                                   Instant previousLastScannedAt) {
        // TODO RFC 0008 §3: per-target/org override lookup via NotificationSettings repository.
        Settings settings = appYmlFallback();

        if (!settings.enabled()) {
            log.debug("Expiry notifications disabled (settings.enabled=false), skipping cert {}",
                    cert.getId());
            return false;
        }

        Instant now = Instant.now();
        long daysLeft = ChronoUnit.DAYS.between(now, cert.getExpiryDate());

        if (daysLeft > settings.warningDays()) {
            // Not yet in the alert window — no stamp, no dispatch.
            return false;
        }

        String severity = (daysLeft <= settings.criticalDays()) ? "CRITICAL" : "WARNING";

        if (mode == EvaluationMode.SCHEDULED) {
            Instant dedupCutoff = now.minus(settings.dedupHours(), ChronoUnit.HOURS);
            if (cert.getLastAlertSentAt() != null
                    && cert.getLastAlertSentAt().isAfter(dedupCutoff)) {
                log.debug("Skipping cert {} — alerted within dedup window (lastAlertSentAt={})",
                        cert.getId(), cert.getLastAlertSentAt());
                return false;
            }
        } else {
            // FORCE mode — check debounce against the pre-scan lastScannedAt.
            if (previousLastScannedAt != null) {
                Instant debounceCutoff = now.minus(forceScanDebounceSeconds, ChronoUnit.SECONDS);
                if (previousLastScannedAt.isAfter(debounceCutoff)) {
                    log.debug("Suppressing FORCE alert for cert {} — target scanned recently at {}",
                            cert.getId(), previousLastScannedAt);
                    return false;
                }
            }
            // FORCE bypasses the dedup gate — no lastAlertSentAt check.
        }

        Target target = cert.getTarget();
        log.debug("Expiry alert queued — target {}:{} daysLeft={} severity={} mode={}",
                target != null ? target.getHost() : "?",
                target != null ? target.getPort() : 0,
                daysLeft, severity, mode);

        // Stamp synchronously in the caller's transaction — durable regardless of email outcome.
        certRepository.stampAlertSentAt(cert.getId(), now);

        // Dispatch email after commit — SMTP failure never rolls back the sweep/scan transaction.
        final int daysLeftFinal = (int) daysLeft;
        final String severityFinal = severity;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notificationService.dispatchExpiryAlert(cert, daysLeftFinal, severityFinal);
                }
            });
        } else {
            // No active transaction (e.g. called from a non-transactional context in tests).
            // Fall through to direct dispatch so behaviour is still correct.
            notificationService.dispatchExpiryAlert(cert, daysLeftFinal, severityFinal);
        }

        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Computes {@code CertStatus} label for scan persisters using the same resolved
     * warningDays threshold as the evaluation algorithm. Centralises what was previously
     * duplicated across {@code SslScannerService} and (hardcoded to 30) in
     * {@code AgentService.determineStatus} (RFC 0008 §2.3).
     */
    public com.certguard.enums.CertStatus determineCertStatus(Instant expiry) {
        long days = ChronoUnit.DAYS.between(Instant.now(), expiry);
        // TODO RFC 0008 §3: derive warningDays/criticalDays from resolved per-target settings.
        if (days < 0) return com.certguard.enums.CertStatus.EXPIRED;
        if (days <= warningDays) return com.certguard.enums.CertStatus.EXPIRING;
        return com.certguard.enums.CertStatus.VALID;
    }

    /**
     * App-yml fallback settings — the only tier until notification_settings (RFC 0008 §3).
     * This is the intentional seam: when §3 is implemented, replace/augment this call
     * with a per-target/org lookup before falling back here.
     */
    private Settings appYmlFallback() {
        return new Settings(true, warningDays, criticalDays, dedupHours);
    }
}
