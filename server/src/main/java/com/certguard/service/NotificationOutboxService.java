package com.certguard.service;

import com.certguard.dto.internal.ExpiryAlertContext;
import com.certguard.dto.internal.RevocationAlertContext;
import com.certguard.entity.NotificationOutbox;
import com.certguard.enums.OutboxStatus;
import com.certguard.repository.NotificationOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable notification outbox (R19 fix).
 *
 * <h3>Enqueue (write path)</h3>
 * {@link #enqueueExpiryAlert} / {@link #enqueueRevocationAlert} are called
 * synchronously — inside the caller's own transaction, NOT via an AFTER_COMMIT
 * hook or {@code @Async} — by {@link ExpiryEvaluationService} immediately after
 * it stamps the dedup column. Because both the stamp and the outbox insert
 * commit atomically, an SMTP outage can no longer cause the dedup gate to
 * suppress an alert that was never actually sent.
 *
 * <h3>Drain (read path)</h3>
 * {@link #findDueIds} / {@link #processOne} are called by
 * {@link NotificationOutboxScheduler} from a separate bean (not self-invoked),
 * so {@code @Transactional} on {@link #processOne} is honoured by the Spring
 * proxy. Each row is processed in its own transaction so one bad row cannot
 * roll back the rest of the batch.
 *
 * <h3>Retention purge</h3>
 * {@link #findPurgeableIds} / {@link #purgeBatch} are, likewise, called by
 * {@link NotificationOutboxScheduler} in a find-then-delete loop until a batch comes back
 * short — SENT rows are retained 30 days, FAILED rows 90 (they're forensic evidence of a
 * real delivery failure and outlive routine confirmations). See {@code V44} for the
 * companion index and the {@code aggregateDeliveryStatus} predicate it depends on.
 */
@Service
@Transactional(readOnly = true)
public class NotificationOutboxService {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxService.class);

    /**
     * Marker written to a SENT row's last_error when app.dev-mode suppressed the send (no
     * SMTP transaction occurred). Not an error — SENT is still the correct terminal status
     * for dev mode — but the row must stay self-describing so a promoted/inspected DB
     * doesn't report a clean delivery for mail that never left the box.
     */
    static final String DEV_MODE_SUPPRESSED_MARKER = "DEV_MODE: send suppressed, no SMTP transaction";

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationService notificationService;

    @Value("${app.outbox.max-attempts:6}")
    private int maxAttempts;

    /** Base for exponential backoff in seconds: attempt N waits base * 2^(N-1), clamped below. */
    @Value("${app.outbox.backoff-base-seconds:60}")
    private int backoffBaseSeconds;

    /** Upper bound on a single backoff interval — retries settle into a steady poll at this rate. */
    @Value("${app.outbox.backoff-max-seconds:3600}")
    private long backoffMaxSeconds;

    public NotificationOutboxService(NotificationOutboxRepository outboxRepository,
                                     NotificationService notificationService) {
        this.outboxRepository = outboxRepository;
        this.notificationService = notificationService;
    }

    // ── Enqueue ──────────────────────────────────────────────────────────────

    /**
     * Inserts one PENDING outbox row per resolved email recipient. Must be called
     * from within an active write transaction (the caller's stamp + this insert commit
     * together). Returns the number of rows enqueued (0 when no email channel/recipients
     * are configured — mirrors the previous "no channels configured" no-op).
     */
    @Transactional
    public int enqueueExpiryAlert(ExpiryAlertContext ctx) {
        List<String> addresses = notificationService.resolveEmailAddresses(ctx.channels());
        if (addresses.isEmpty()) {
            log.warn("No email recipients resolved for expiry alert on {}:{} (org {}), cert {} — nothing enqueued",
                    ctx.host(), ctx.port(), ctx.orgId(), ctx.certId());
            return 0;
        }

        String subject      = notificationService.buildExpirySubject(ctx);
        String templateName = notificationService.expiryTemplateName(ctx);
        Map<String, Object> vars = notificationService.buildExpiryTemplateVars(ctx);

        for (String address : addresses) {
            enqueue(ctx.orgId(), address, subject, templateName, vars);
        }
        return addresses.size();
    }

    /** Revocation-alert counterpart of {@link #enqueueExpiryAlert}. */
    @Transactional
    public int enqueueRevocationAlert(RevocationAlertContext ctx) {
        List<String> addresses = notificationService.resolveEmailAddresses(ctx.channels());
        if (addresses.isEmpty()) {
            log.warn("No email recipients resolved for revocation alert on {}:{} (org {}), cert {} — nothing enqueued",
                    ctx.host(), ctx.port(), ctx.orgId(), ctx.certId());
            return 0;
        }

        String subject      = notificationService.buildRevocationSubject(ctx);
        String templateName = "revocation-alert";
        Map<String, Object> vars = notificationService.buildRevocationTemplateVars(ctx);

        for (String address : addresses) {
            enqueue(ctx.orgId(), address, subject, templateName, vars);
        }
        return addresses.size();
    }

    private void enqueue(UUID orgId, String toAddress, String subject,
                         String templateName, Map<String, Object> templateVars) {
        NotificationOutbox row = NotificationOutbox.builder()
                .orgId(orgId)
                .toAddress(toAddress)
                .subject(subject)
                .templateName(templateName)
                .templateVars(templateVars)
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(Instant.now())
                .build();
        outboxRepository.save(row);
    }

    // ── Drain ────────────────────────────────────────────────────────────────

    /** IDs of PENDING rows due for a send attempt, oldest-due first, capped at {@code limit}. */
    public List<UUID> findDueIds(int limit) {
        return outboxRepository.findDueIds(OutboxStatus.PENDING, Instant.now(), PageRequest.of(0, limit));
    }

    /**
     * Sends one outbox row and persists the outcome. Never lets an exception escape:
     * a bad row is recorded (attempts++, backoff or FAILED) and the scheduler's loop
     * continues to the next row.
     *
     * @return true if the row was sent (or no longer needed processing), false if it
     *         was recorded as a retry/failure
     */
    @Transactional
    public boolean processOne(UUID id) {
        Optional<NotificationOutbox> opt = outboxRepository.findById(id);
        if (opt.isEmpty()) return true; // already removed/processed — nothing to do

        NotificationOutbox row = opt.get();
        if (row.getStatus() != OutboxStatus.PENDING) return true; // race: another drain already claimed it

        try {
            boolean suppressed = notificationService.sendOutboxEmail(
                    row.getToAddress(), row.getSubject(), row.getTemplateName(), row.getTemplateVars());
            row.setStatus(OutboxStatus.SENT);
            row.setSentAt(Instant.now());
            // Dev mode is a legitimate no-op send (no SMTP transaction occurred). Stamp the
            // row so it stays distinguishable from a real delivery — the delivery-status
            // query only surfaces last_error for queued-or-failed rows, so this marker does
            // not leak into the UI banner for genuinely healthy (non-dev) sends.
            row.setLastError(suppressed ? DEV_MODE_SUPPRESSED_MARKER : null);
            outboxRepository.save(row);
            return true;
        } catch (Exception e) {
            recordFailure(row, e);
            return false;
        }
    }

    private void recordFailure(NotificationOutbox row, Exception e) {
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setLastError(truncate(e.getMessage()));

        if (attempts >= maxAttempts) {
            row.setStatus(OutboxStatus.FAILED);
            log.error("Outbox row {} to={} exhausted {} attempts — marking FAILED: {}",
                    row.getId(), row.getToAddress(), attempts, e.getMessage());
        } else {
            row.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds(attempts)));
            log.warn("Outbox row {} to={} attempt {}/{} failed — retrying in {}s: {}",
                    row.getId(), row.getToAddress(), attempts, maxAttempts,
                    backoffSeconds(attempts), e.getMessage());
        }
        outboxRepository.save(row);
    }

    /**
     * Exponential backoff, clamped to {@code backoff-max-seconds}.
     * <p>
     * The clamp is not cosmetic: {@code maxAttempts} is deliberately high so a multi-day
     * relay outage does not exhaust rows into terminal FAILED before the relay is restored
     * (see R19). Without a cap, {@code base * 2^(N-1)} both grows past any useful interval
     * and overflows the shift once {@code N > 63}, which would yield a negative or absurd
     * delay. The exponent is bounded before shifting for the same reason.
     */
    private long backoffSeconds(int attempts) {
        int exponent = Math.min(attempts - 1, 32);
        long backoff = backoffBaseSeconds * (1L << exponent);
        return Math.min(backoff, backoffMaxSeconds);
    }

    private String truncate(String message) {
        if (message == null) return "Unknown error";
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    // ── Retention purge ─────────────────────────────────────────────────────

    /**
     * IDs of one purge-eligible batch for the given terminal status, capped at
     * {@code batchSize}. Called from {@link NotificationOutboxScheduler}, which loops
     * find-then-{@link #purgeBatch} until a batch comes back short of {@code batchSize} —
     * see that class for why the loop lives there rather than here (mirrors the
     * {@link #findDueIds}/{@link #processOne} split: keeping each DB round-trip a separate
     * proxied call from a different bean).
     *
     * @param status must be {@link OutboxStatus#SENT} or {@link OutboxStatus#FAILED} —
     *               PENDING rows are never purge-eligible, they're either still retrying
     *               or awaiting their first attempt.
     */
    public List<UUID> findPurgeableIds(OutboxStatus status, Instant cutoff, int batchSize) {
        Pageable page = PageRequest.of(0, batchSize);
        return switch (status) {
            case SENT -> outboxRepository.findSentIdsOlderThan(cutoff, page);
            case FAILED -> outboxRepository.findFailedIdsOlderThan(cutoff, page);
            case PENDING -> throw new IllegalArgumentException("PENDING rows are never purge-eligible");
        };
    }

    /**
     * Hard-deletes one batch of rows by ID in a single bulk statement (not per-entity loads +
     * deletes) — {@code deleteAllByIdInBatch} issues one {@code DELETE ... WHERE id IN (...)}.
     * Own transaction (see {@link #findPurgeableIds} javadoc): a purge sweep that finds many
     * batches commits progress after each one rather than holding one long-running deletion
     * transaction across the whole sweep.
     *
     * @return number of rows deleted (equal to {@code ids.size()} — provided for the caller's
     *         running total and short-batch loop termination check)
     */
    @Transactional
    public int purgeBatch(List<UUID> ids) {
        if (ids.isEmpty()) return 0;
        outboxRepository.deleteAllByIdInBatch(ids);
        return ids.size();
    }
}
