package com.certguard.dto.response;

import java.time.Instant;

/**
 * Returned by POST /api/v1/anon/sessions.
 * Both tokens are raw values — never stored; SHA-256 hashes only in DB.
 */
public record AnonSessionCreateResponse(
        String scanToken,
        String viewToken,
        Instant scanExpiresAt,
        Instant viewExpiresAt,
        String dashboardUrl
) {}
