package com.certguard.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Renewal status DTO returned by certguard-renewal-service (via RenewalServiceClient).
 * Uses String for status and caProvider to avoid coupling to renewal service enums.
 */
public record RenewalResponse(
        UUID id,
        UUID certId,
        UUID targetId,
        UUID agentId,
        String status,
        String caProvider,
        String targetInstallPath,
        UUID packageId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {}
