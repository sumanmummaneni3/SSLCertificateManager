package com.certguard.dto.internal;

import java.util.List;
import java.util.UUID;

/**
 * Sent from core to certguard-renewal-service when a user initiates a renewal.
 * Core validates cert/target/agent and passes all needed data so the renewal service
 * does not need to call back into core for cert metadata.
 */
public record InternalCreateRenewalRequest(
        UUID orgId,
        UUID certId,
        UUID targetId,
        UUID agentId,
        UUID requestedBy,
        String caProvider,
        String targetInstallPath,
        String commonName,
        List<String> sans
) {}
