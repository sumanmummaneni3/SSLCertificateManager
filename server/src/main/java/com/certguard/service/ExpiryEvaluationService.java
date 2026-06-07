package com.certguard.service;

import com.certguard.dto.internal.ExpiryAlertContext;
import com.certguard.entity.CertificateRecord;
import com.certguard.entity.NotificationSettings;
import com.certguard.entity.Target;
import com.certguard.enums.CertStatus;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.repository.NotificationSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Single convergence point for certificate expiry evaluation and notification
 * dispatch (RFC 0008 §2 + §3).
 *
 * <h3>Resolution chain (RFC 0008 §3.2)</h3>
 * <ol>
 *   <li>Per-target override row ({@code notification_settings.target_id = cert.target_id})</li>
 *   <li>Org-default row ({@code notification_settings.org_id = cert.org_id, target_id IS NULL})</li>
 *   <li>App-yml fallback ({@code @Value} fields — always available)</li>
 * </ol>
 *
 * <h3>Sweep N+1 mitigation (RFC 0008 §3.3)</h3>
 * The batch {@link #evaluateAndNotify(Collection, EvaluationMode)} overload pre-loads
 * all settings in two queries (target-overrides + org-defaults) before iterating certs.
 * No per-cert DB round-trips for settings.
 *
 * <h3>AFTER_COMMIT dispatch</h3>
 * Stamps {@code last_alert_sent_at} synchronously in the caller's transaction, then
 * registers an AFTER_COMMIT hook to call {@code NotificationService.dispatchExpiryAlert}
 * (still {@code @Async}). SMTP I/O never rolls back the sweep/scan transaction.
 *
 * <h3>FORCE-scan debounce (RFC 0008 §5)</h3>
 * A FORCE evaluation is suppressed when {@code previousLastScannedAt} is more recent
 * than {@code app.alert.force-scan-debounce-seconds}. FORCE bypasses the dedup gate
 * but still writes the stamp.
 */
@Service
@Transactional(readOnly = true)
public class ExpiryEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryEvaluationService.class);

    public enum EvaluationMode { SCHEDULED, FORCE }

    /** Immutable resolved settings for a single evaluation. */
    record Settings(boolean enabled, int warningDays, int criticalDays, int dedupHours) {}

    private final NotificationService notificationService;
    private final CertificateRecordRepository certRepository;
    private final NotificationSettingsRepository settingsRepository;

    // App-yml fallback thresholds (last tier in the resolution chain).
    @Value("${app.alert.warning-days:30}")  private int warningDays;
    @Value("${app.alert.critical-days:7}") private int criticalDays;
    @Value("${app.alert.dedup-hours:23}")  private int dedupHours;
    @Value("${app.alert.force-scan-debounce-seconds:120}") private int forceScanDebounceSeconds;

    public ExpiryEvaluationService(NotificationService notificationService,
                                   CertificateRecordRepository certRepository,
                                   NotificationSettingsRepository settingsRepository) {
        this.notificationService = notificationService;
        this.certRepository = certRepository;
        this.settingsRepository = settingsRepository;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Batch form for the daily expiry sweep (RFC 0008 §3.3 N+1 mitigation).
     *
     * Pre-loads all relevant settings rows in exactly two queries, builds an
     * in-memory resolver map, then iterates certs. No per-cert settings DB calls.
     *
     * @return number of alerts enqueued for dispatch
     */
    @Transactional
    public int evaluateAndNotify(Collection<CertificateRecord> certs, EvaluationMode mode) {
        if (certs.isEmpty()) return 0;

        // Collect target and org ids from the candidate set.
        Set<UUID> targetIds = certs.stream()
                .filter(c -> c.getTarget() != null)
                .map(c -> c.getTarget().getId())
                .collect(Collectors.toSet());
        Set<UUID> orgIds = certs.stream()
                .map(CertificateRecord::getOrgId)
                .collect(Collectors.toSet());

        // Two batch queries — no per-cert round-trips.
        Map<UUID, NotificationSettings> byTargetId = settingsRepository
                .findByTargetIdIn(targetIds).stream()
                .collect(Collectors.toMap(ns -> ns.getTarget().getId(), Function.identity()));

        Map<UUID, NotificationSettings> byOrgId = settingsRepository
                .findByOrgIdInAndTargetIsNull(orgIds).stream()
                .collect(Collectors.toMap(ns -> ns.getOrganization().getId(), Function.identity()));

        int dispatched = 0;
        for (CertificateRecord cert : certs) {
            Settings settings = resolveFromMaps(cert, byTargetId, byOrgId);
            if (evaluateSingle(cert, mode, null, settings)) dispatched++;
        }
        return dispatched;
    }

    /**
     * Single-cert form. Called from scan write-paths after the cert row is saved.
     * Resolves settings via DB (one target lookup + one org lookup at most — acceptable
     * for the single-cert path; the batch path uses the pre-loaded maps).
     *
     * @param previousLastScannedAt target's {@code lastScannedAt} captured BEFORE this
     *                              scan overwrote it; used for FORCE debounce.
     */
    @Transactional
    public void evaluateAndNotify(CertificateRecord cert, EvaluationMode mode,
                                  Instant previousLastScannedAt) {
        Settings settings = resolveSettings(cert.getTarget(), cert.getOrgId());
        evaluateSingle(cert, mode, previousLastScannedAt, settings);
    }

    /**
     * Returns the maximum configured {@code warningDays} across all persisted settings rows,
     * falling back to the app.yml value when no rows exist.
     *
     * Used by {@link CertificateExpiryScheduler} to widen the fetch window so that targets
     * with a larger-than-default warning window are not missed (RFC 0008 §3.3).
     */
    public int resolveMaxWarningDays() {
        return settingsRepository.findMaxWarningDays()
                .map(max -> Math.max(max, warningDays))
                .orElse(warningDays);
    }

    /**
     * Derives {@link CertStatus} for a cert using the resolved warningDays for that target.
     * Replaces the duplicated / hardcoded logic that was in SslScannerService and AgentService.
     *
     * @param expiry   the certificate's not-after instant
     * @param target   the target the cert was scanned from (may be null for partial builds)
     * @param orgId    the org id (used as fallback when target is null)
     */
    public CertStatus determineCertStatus(Instant expiry, Target target, UUID orgId) {
        Settings settings = resolveSettings(target, orgId);
        long days = ChronoUnit.DAYS.between(Instant.now(), expiry);
        if (days < 0) return CertStatus.EXPIRED;
        if (days <= settings.warningDays()) return CertStatus.EXPIRING;
        return CertStatus.VALID;
    }

    // ── Core algorithm (RFC 0008 §2.3) ───────────────────────────────────────

    /**
     * Evaluates a single certificate against resolved settings.
     *
     * @return true if an alert was enqueued for dispatch
     */
    private boolean evaluateSingle(CertificateRecord cert, EvaluationMode mode,
                                   Instant previousLastScannedAt, Settings settings) {
        if (!settings.enabled()) {
            log.debug("Expiry notifications disabled for cert {} — skipping", cert.getId());
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

        // P1-A fix: resolve all entity data WHILE THE SESSION IS STILL OPEN (before commit).
        // The AFTER_COMMIT callback and the @Async dispatch run on threads with no Hibernate
        // session; passing a managed entity across that boundary causes LazyInitializationException
        // on target.getOrganization() / target.getAgent() which silently kills the alert.
        // Build an ExpiryAlertContext from plain values now, and pass only the context.
        final ExpiryAlertContext alertCtx = buildAlertContext(cert, (int) daysLeft, severity);

        // Dispatch after commit — SMTP failure never rolls back the sweep/scan transaction.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notificationService.dispatchExpiryAlert(alertCtx);
                }
            });
        } else {
            // No active Spring transaction (e.g., unit tests without a real TX context).
            notificationService.dispatchExpiryAlert(alertCtx);
        }

        return true;
    }

    // ── Resolution chain ──────────────────────────────────────────────────────

    /**
     * Single-cert resolution: per-target → org-default → app.yml fallback.
     * Issues at most two DB queries (one per tier hit); acceptable for scan write-paths.
     */
    private Settings resolveSettings(Target target, UUID orgId) {
        if (target != null) {
            Optional<NotificationSettings> perTarget =
                    settingsRepository.findByTargetId(target.getId());
            if (perTarget.isPresent()) return toSettings(perTarget.get());
        }

        UUID resolvedOrgId = (orgId != null) ? orgId
                : (target != null && target.getOrganization() != null
                        ? target.getOrganization().getId() : null);

        if (resolvedOrgId != null) {
            Optional<NotificationSettings> orgDefault =
                    settingsRepository.findByOrganizationIdAndTargetIsNull(resolvedOrgId);
            if (orgDefault.isPresent()) return toSettings(orgDefault.get());
        }

        return appYmlFallback();
    }

    /**
     * Batch-mode resolution from pre-loaded maps — no DB calls.
     */
    private Settings resolveFromMaps(CertificateRecord cert,
                                     Map<UUID, NotificationSettings> byTargetId,
                                     Map<UUID, NotificationSettings> byOrgId) {
        if (cert.getTarget() != null) {
            NotificationSettings perTarget = byTargetId.get(cert.getTarget().getId());
            if (perTarget != null) return toSettings(perTarget);
        }
        NotificationSettings orgDefault = byOrgId.get(cert.getOrgId());
        if (orgDefault != null) return toSettings(orgDefault);
        return appYmlFallback();
    }

    private Settings toSettings(NotificationSettings ns) {
        return new Settings(ns.getEnabled(), ns.getWarningDays(), ns.getCriticalDays(), ns.getDedupHours());
    }

    private Settings appYmlFallback() {
        return new Settings(true, warningDays, criticalDays, dedupHours);
    }

    // ── Pre-commit context builder (P1-A) ─────────────────────────────────────

    /**
     * Captures everything the email dispatch needs into a plain-Java value object
     * while the Hibernate session is still open. Must be called before the
     * transaction commits so that lazy associations ({@code target.organization},
     * {@code target.agent}, {@code target.notificationChannels}) are reachable.
     *
     * The returned {@link ExpiryAlertContext} is safe to cross any thread / session
     * boundary — it holds only UUIDs, primitives, Strings, and a Map of plain values.
     */
    private ExpiryAlertContext buildAlertContext(CertificateRecord cert, int daysLeft, String severity) {
        Target target = cert.getTarget();

        // All lazy-association accesses happen here, in-transaction.
        String host   = target != null ? target.getHost() : "unknown";
        int    port   = target != null ? target.getPort() : 0;
        UUID   orgId  = cert.getOrgId();                              // denormalized — no lazy access
        boolean agentDiscovered = target != null && target.getAgent() != null;

        // resolveChannels reads target.notificationChannels (JSONB eager) and may read
        // target.organization.getId() + orgChannelRepository — all safe here in-session.
        Map<String, Object> channels = target != null
                ? notificationService.resolveChannels(target)
                : Map.of();

        return new ExpiryAlertContext(cert.getId(), host, port, daysLeft, severity,
                orgId, agentDiscovered, channels);
    }
}
