package com.certguard.auth.dto.response;

import com.certguard.auth.entity.User;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String name,
        String provider,
        @JsonProperty("email_verified") boolean emailVerified,
        @JsonProperty("created_at") Instant createdAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getName(),
                u.getProviderId(), u.isEmailVerified(), u.getCreatedAt());
    }
}
