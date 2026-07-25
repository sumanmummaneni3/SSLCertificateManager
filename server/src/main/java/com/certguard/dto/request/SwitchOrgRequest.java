package com.certguard.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * RFC 0015 Phase 2 — request body for POST /api/v1/auth/switch-org (local/dev, HS256).
 */
public record SwitchOrgRequest(
        @NotNull UUID orgId
) {}
