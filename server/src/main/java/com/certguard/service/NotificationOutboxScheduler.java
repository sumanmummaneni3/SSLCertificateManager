package com.certguard.service;

import com.certguard.enums.OutboxStatus;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Drains the durable notification outbox (R19 fix, see {@link NotificationOutboxService}).
 *
 * <p>Thin scheduler shell — all enqueue/send/retry logic lives in
 * {@link NotificationOutboxService}. Per-row processing is delegated to that
 * service (a different bean) rather than a self-invoked method, so
 * {@code @Transactional} on {@link NotificationOutboxService#processOne} is
 * actually honoured by the Spring proxy (see the P1-C note in
 * {@code PrivateScanScheduler}).
 *
 * <p>{@link NotificationOutboxService#processOne} never lets an exception escape —
 * it records the failure on the row and returns — but this loop also guards with
 * its own try/catch so a truly unexpected error (e.g. a bug) on one row can never
 * abort the rest of the drain.
 */
@Component
public class NotificationOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxScheduler.class);

    private final NotificationOutboxService outboxService;

    @Value("${app.outbox.batch-size:200}")
    private int batchSize;

    /** How long SENT rows survive before purge — routine delivery confirmations. */
    @Value("${app.outbox.sent-retention-days:30}")
    private int sentRetentionDays;

    /**
     * How long FAILED rows survive before purge. Longer than SENT — these are forensic
     * evidence of a real delivery failure (support escalations, spotting a pattern of
     * bouncing addresses), not routine confirmations, but not indefinite either: keeping
     * them forever "in case it matters" recreates the original unbounded-growth problem in
     * a smaller table.
     */
    @Value("${app.outbox.failed-retention-days:90}")
    private int failedRetentionDays;

    /** Rows deleted per batch — bounded so one sweep is never a single unbounded DELETE. */
    @Value("${app.outbox.purge-batch-size:500}")
    private int purgeBatchSize;

    public NotificationOutboxScheduler(NotificationOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Scheduled(fixedDelayString = "${app.outbox.drain-fixed-delay-ms:60000}")
    @SchedulerLock(name = "NotificationOutboxScheduler_drain",
                   lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void drain() {
        List<UUID> dueIds = outboxService.findDueIds(batchSize);
        if (dueIds.isEmpty()) return;

        log.debug("Notification outbox drain — {} row(s) due", dueIds.size());

        int sent = 0;
        int retriedOrFailed = 0;
        for (UUID id : dueIds) {
            try {
                if (outboxService.processOne(id)) {
                    sent++;
                } else {
                    retriedOrFailed++;
                }
            } catch (Exception e) {
                // Defense in depth: processOne already catches send failures internally.
                // This guards against an unexpected bug in processOne itself so one bad
                // row can never abort the rest of the drain.
                log.error("Unexpected error processing outbox row {} — skipping", id, e);
                retriedOrFailed++;
            }
        }

        log.info("Notification outbox drain complete — {} sent, {} retried/failed, {} considered",
                sent, retriedOrFailed, dueIds.size());
    }

    /**
     * Daily retention purge — hard-deletes SENT rows older than
     * {@code app.outbox.sent-retention-days} and FAILED rows older than
     * {@code app.outbox.failed-retention-days}. Runs at 05:30, deliberately off the busiest
     * scheduling window: the public scan fires at 02:00, the private sweep at 03:00, and a
     * long-running public scan can still be in flight at 02:30 — 05:30 sits clear of the
     * revocation recheck (04:00) and expiry sweep (08:00) too.
     *
     * <p>Each status is purged in its own find-then-delete loop, batched at
     * {@code app.outbox.purge-batch-size} rows, looping until a batch comes back short of the
     * limit — never a single unbounded {@code DELETE}. Each batch is its own transaction (see
     * {@link NotificationOutboxService#purgeBatch}), so a sweep spanning many batches commits
     * progress incrementally rather than holding one long-running deletion transaction.
     */
    @Scheduled(cron = "${app.outbox.purge-schedule-cron:0 30 5 * * *}")
    @SchedulerLock(name = "NotificationOutboxScheduler_purge",
                   lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void purgeExpiredRows() {
        int sentPurged = purgeStatus(OutboxStatus.SENT, sentRetentionDays);
        int failedPurged = purgeStatus(OutboxStatus.FAILED, failedRetentionDays);
        log.info("Notification outbox purge complete — {} SENT (>{}d), {} FAILED (>{}d) deleted",
                sentPurged, sentRetentionDays, failedPurged, failedRetentionDays);
    }

    private int purgeStatus(OutboxStatus status, int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int total = 0;
        List<UUID> batch;
        do {
            batch = outboxService.findPurgeableIds(status, cutoff, purgeBatchSize);
            total += outboxService.purgeBatch(batch);
        } while (batch.size() == purgeBatchSize);
        return total;
    }
}
