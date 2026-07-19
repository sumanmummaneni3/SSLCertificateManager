package com.certguard.service;

import com.certguard.dto.response.NotificationDeliveryStatusResponse;
import com.certguard.repository.DeliveryStatusAggregate;
import com.certguard.repository.NotificationOutboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Backs {@code GET /api/v1/organizations/{orgId}/notifications/delivery-status} — the
 * data source for a UI banner warning org members that expiry/revocation alert emails are
 * not being delivered.
 *
 * <p>Read-only, aggregate-only: never loads {@link com.certguard.entity.NotificationOutbox}
 * rows, and never returns recipient email addresses (only counts/timestamps/error text).
 */
@Service
@Transactional(readOnly = true)
public class NotificationDeliveryStatusService {

    private final NotificationOutboxRepository outboxRepository;

    public NotificationDeliveryStatusService(NotificationOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * @param orgId          tenant to report on; caller access to this org is enforced by the
     *                       controller (role + {@code MspAccessGuard}), not here.
     * @param includeLastError whether to populate {@code lastError} — the controller passes
     *                       {@code true} only for callers holding an org-admin role.
     */
    public NotificationDeliveryStatusResponse getDeliveryStatus(UUID orgId, boolean includeLastError) {
        DeliveryStatusAggregate agg = outboxRepository.aggregateDeliveryStatus(orgId);
        boolean degraded = agg.queuedCount() > 0 || agg.failedCount() > 0;

        String lastError = null;
        if (includeLastError && degraded) {
            List<String> recent = outboxRepository.findRecentErrors(orgId, PageRequest.of(0, 1));
            lastError = recent.isEmpty() ? null : recent.get(0);
        }

        return NotificationDeliveryStatusResponse.builder()
                .degraded(degraded)
                .queuedCount((int) agg.queuedCount())
                .failedCount((int) agg.failedCount())
                .oldestQueuedAt(agg.oldestUndeliveredAt())
                .nextAttemptAt(agg.nextAttemptAt())
                .lastError(lastError)
                .build();
    }
}
