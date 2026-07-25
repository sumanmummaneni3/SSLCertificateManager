package com.certguard.auth.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * RFC 0015 Phase 2 — request body for POST /api/auth/switch-org (authoritative, RS256).
 */
public record SwitchOrgRequest(
        @NotNull UUID orgId
) {}
