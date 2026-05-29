package com.certguard.dto.internal;

import java.util.UUID;

/**
 * Sent by certguard-renewal-service to trigger an email notification in core.
 */
public record InternalRenewalNotificationRequest(
        String eventType,          // RENEWAL_READY | RENEWAL_INSTALLED | RENEWAL_FAILED
        UUID renewalId,
        UUID orgId,
        UUID requestedBy,
        String targetInstallPath,
        String fileName,           // for RENEWAL_READY
        String checksumSha256,     // for RENEWAL_READY
        String errorDetail         // for RENEWAL_FAILED
) {}
