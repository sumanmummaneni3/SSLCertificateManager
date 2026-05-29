package com.certguard.dto.internal;

/**
 * Sent from core to certguard-renewal-service to report delivery completion or failure.
 */
public record InternalDeliveryEventRequest(
        String errorDetail   // null for completed events, non-null for failed events
) {}
