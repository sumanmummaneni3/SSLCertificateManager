package com.certguard.dto.response;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Response for {@code GET /api/v1/organizations/{orgId}/notifications/delivery-status}.
 *
 * <p>Backs the UI banner that warns org members when expiry/revocation alert emails are
 * not being delivered. Sourced from {@link com.certguard.entity.NotificationOutbox}
 * (R19 durable outbox).
 *
 * <p>{@code degraded} is true when there is at least one PENDING row that has already
 * failed once ({@code attempts > 0}, still retrying) or at least one terminal FAILED row —
 * both states count, since during an SMTP outage rows can sit PENDING for a long time
 * (see {@code app.outbox.max-attempts}) before ever reaching FAILED.
 *
 * <p>{@code lastError} carries raw SMTP diagnostics and is populated by the service only
 * when the caller holds an org-admin role; it is always {@code null} for non-admin members.
 * Recipient email addresses are never included in this response, for any role.
 */
@Value
@Builder
public class NotificationDeliveryStatusResponse {

    boolean degraded;
    int queuedCount;
    int failedCount;
    Instant oldestQueuedAt;
    Instant nextAttemptAt;
    String lastError;
}
