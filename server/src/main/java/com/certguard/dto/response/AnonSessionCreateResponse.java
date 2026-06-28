package com.certguard.dto.response;

import java.time.Instant;

/**
 * Returned by POST /api/v1/anon/sessions.
 * Both tokens are raw values — never stored; SHA-256 hashes only in DB.
 *
 * {@code downloadUrl} — pre-stamped download link for the agent ZIP bundle
 *   ({@code /api/v1/anon/download?token=<scanToken>}).
 * {@code dashboardUrl} — public read-only results dashboard link.
 */
public record AnonSessionCreateResponse(
        String scanToken,
        String viewToken,
        Instant scanExpiresAt,
        Instant viewExpiresAt,
        String dashboardUrl,
        String downloadUrl
) {}
