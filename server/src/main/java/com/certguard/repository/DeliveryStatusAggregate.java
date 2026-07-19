package com.certguard.repository;

import java.time.Instant;

/**
 * JPQL constructor-projection for {@link NotificationOutboxRepository#aggregateDeliveryStatus}.
 *
 * <p>Backs {@code GET /api/v1/organizations/{orgId}/notifications/delivery-status}. See that
 * query's Javadoc for the precise definition of "queued" vs "failed".
 */
public record DeliveryStatusAggregate(
        long queuedCount,
        long failedCount,
        Instant oldestUndeliveredAt,
        Instant nextAttemptAt) {
}
