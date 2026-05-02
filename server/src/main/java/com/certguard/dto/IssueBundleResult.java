package com.certguard.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal service return type for AgentBundleService.issueBundle().
 * Not serialised to JSON — converted to IssueBundleResponse in the controller.
 */
public record IssueBundleResult(
        UUID agentId,
        String installKey,
        String bundleDownloadUrl,
        Instant expiresAt
) {}
