package com.certguard.repository;

import com.certguard.entity.NotificationOutbox;
import com.certguard.enums.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {

    /**
     * IDs of PENDING rows whose {@code next_attempt_at} is due, oldest-due first,
     * capped by {@code pageable}'s page size. Returns IDs only (not entities) so the
     * scheduler processes each row in its own short transaction rather than holding
     * a large batch of managed entities across the whole drain.
     */
    @Query("SELECT o.id FROM NotificationOutbox o "
            + "WHERE o.status = :status AND o.nextAttemptAt <= :now "
            + "ORDER BY o.nextAttemptAt ASC")
    List<UUID> findDueIds(@Param("status") OutboxStatus status,
                          @Param("now") Instant now,
                          Pageable pageable);

    /**
     * Single aggregate query backing {@code GET .../notifications/delivery-status}. Computed
     * in the DB rather than loading rows, since this is polled by the UI banner.
     *
     * <p>"Queued" = PENDING rows that have already failed at least once ({@code attempts > 0})
     * and are still retrying — deliberately NOT all PENDING rows, since {@code attempts = 0}
     * rows are simply awaiting their imminent first send attempt and are not evidence of a
     * delivery problem. "Failed" = terminal FAILED rows.
     *
     * <p>{@code oldestUndeliveredAt} / {@code nextAttemptAt} are scoped to that same
     * queued-or-failed definition (nextAttemptAt only applies to the queued/PENDING subset —
     * FAILED rows are terminal and never retried). This matters operationally: with
     * {@code app.outbox.max-attempts} high enough to ride out a multi-day SMTP outage, rows
     * sit PENDING with {@code attempts > 0} for a long time before ever reaching FAILED — a
     * status view that only counted FAILED would report "healthy" throughout the entire
     * outage.
     *
     * <p>Returns a single row even when the org has zero outbox rows (plain SQL aggregate
     * semantics without GROUP BY): counts are 0, timestamps are {@code null}.
     *
     * <p>The {@code o.status <> SENT} predicate is semantically a no-op — every aggregate
     * expression above already ignores SENT rows (queued = PENDING with attempts&gt;0, failed
     * = FAILED, both MIN(CASE...) consider only those two) — but it lets the partial index
     * {@code idx_notification_outbox_org_not_sent} (V44) actually serve this query instead of
     * declaring an index the planner can never use for a query that still visits every row.
     * This query is polled by the UI banner every few minutes per active session, so the
     * table-scan this avoids matters once SENT rows accumulate at scale (see V44 rationale).
     */
    @Query("SELECT new com.certguard.repository.DeliveryStatusAggregate("
            + "COUNT(CASE WHEN o.status = com.certguard.enums.OutboxStatus.PENDING AND o.attempts > 0 THEN 1 END), "
            + "COUNT(CASE WHEN o.status = com.certguard.enums.OutboxStatus.FAILED THEN 1 END), "
            + "MIN(CASE WHEN (o.status = com.certguard.enums.OutboxStatus.PENDING AND o.attempts > 0) "
            + "          OR o.status = com.certguard.enums.OutboxStatus.FAILED THEN o.createdAt END), "
            + "MIN(CASE WHEN o.status = com.certguard.enums.OutboxStatus.PENDING AND o.attempts > 0 THEN o.nextAttemptAt END)) "
            + "FROM NotificationOutbox o "
            + "WHERE o.orgId = :orgId AND o.status <> com.certguard.enums.OutboxStatus.SENT")
    DeliveryStatusAggregate aggregateDeliveryStatus(@Param("orgId") UUID orgId);

    /**
     * Most recent {@code last_error} among the queued-or-failed rows for the org (see
     * {@link #aggregateDeliveryStatus}), newest first. Admin-only in the service layer —
     * carries raw SMTP diagnostics. Callers should cap the {@link Pageable} to size 1.
     */
    @Query("SELECT o.lastError FROM NotificationOutbox o "
            + "WHERE o.orgId = :orgId AND o.lastError IS NOT NULL "
            + "AND ((o.status = com.certguard.enums.OutboxStatus.PENDING AND o.attempts > 0) "
            + "     OR o.status = com.certguard.enums.OutboxStatus.FAILED) "
            + "ORDER BY o.updatedAt DESC")
    List<String> findRecentErrors(@Param("orgId") UUID orgId, Pageable pageable);

    /**
     * IDs of terminal SENT rows past the retention cutoff, capped by {@code pageable}'s page
     * size — one purge batch (see {@link com.certguard.service.NotificationOutboxScheduler}).
     * Anchored on {@code sentAt} (not {@code updatedAt}): that column is stamped once, exactly
     * when the row completed, and is never touched again.
     *
     * <p>Deliberately unordered: the caller loops batch-select-then-delete until a batch comes
     * back short, so ordering the candidate set adds sort cost for no correctness benefit —
     * every eligible row is purged eventually regardless of which ones go first.
     */
    @Query("SELECT o.id FROM NotificationOutbox o "
            + "WHERE o.status = com.certguard.enums.OutboxStatus.SENT AND o.sentAt < :cutoff")
    List<UUID> findSentIdsOlderThan(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * IDs of terminal FAILED rows past the retention cutoff, capped by {@code pageable}'s page
     * size. Anchored on {@code updatedAt} (BaseEntity's DB-trigger-maintained column, see V1's
     * {@code update_updated_at()}): FAILED rows have no dedicated "failed_at" column, but
     * {@code recordFailure} writes the row exactly once more when it transitions PENDING to
     * FAILED, so {@code updatedAt} at that point is the failure timestamp.
     */
    @Query("SELECT o.id FROM NotificationOutbox o "
            + "WHERE o.status = com.certguard.enums.OutboxStatus.FAILED AND o.updatedAt < :cutoff")
    List<UUID> findFailedIdsOlderThan(@Param("cutoff") Instant cutoff, Pageable pageable);
}
