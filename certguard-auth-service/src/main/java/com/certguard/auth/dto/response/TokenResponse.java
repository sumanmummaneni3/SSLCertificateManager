package com.certguard.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenResponse(
        String token,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("user_id") String userId,
        String provider,
        String email,
        String name
) {
    public static TokenResponse of(String token, long expiresInSeconds,
                                   String userId, String provider, String email, String name) {
        return new TokenResponse(token, "Bearer", expiresInSeconds, userId, provider, email, name);
    }
}
