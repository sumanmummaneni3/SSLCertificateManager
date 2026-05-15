package com.certguard.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record ValidateResponse(
        boolean valid,
        @JsonProperty("user_id") String userId,
        String provider,
        String email,
        String name,
        @JsonProperty("provider_ids") Map<String, String> providerIds,
        long exp,
        long iat,
        String iss
) {}
