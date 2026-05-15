package com.certguard.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body for POST /api/auth/token.
 *
 * Google flow:  provider=google,  idToken=<Google ID token>
 * Microsoft:    provider=microsoft, code=<auth code>, redirectUri=<same as initiate>
 * Email:        provider=email,   email=<addr>, password=<pass>
 */
public record TokenRequest(
        @NotBlank
        @Pattern(regexp = "google|microsoft|email")
        String provider,

        /** Google: ID token from gsi/One Tap */
        String idToken,

        /** Microsoft: authorization code from redirect */
        String code,

        /** Microsoft: must match the URI used in the authorization request */
        String redirectUri,

        /** Email auth */
        String email,

        /** Email auth */
        String password
) {}
