package com.certguard.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body for POST /api/auth/initiate */
public record InitiateRequest(
        @NotBlank
        @Pattern(regexp = "google|microsoft|email", message = "provider must be google, microsoft, or email")
        String provider,

        /** Required only for provider=email; ignored for OAuth flows. */
        String redirectUri
) {}
