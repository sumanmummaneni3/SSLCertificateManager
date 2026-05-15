package com.certguard.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InitiateResponse(
        String provider,
        @JsonProperty("auth_url") String authUrl,
        /** Non-null for email flow — client should POST credentials to /api/auth/token directly. */
        String message
) {}
