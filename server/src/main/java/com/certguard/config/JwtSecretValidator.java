package com.certguard.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Fails fast on startup if JWT_SECRET is missing or shorter than 64 characters.
 * A minimum of 64 characters (≥512 bits assuming ASCII) ensures HMAC-SHA256 keys
 * are of sufficient strength for HS256/HS512 JWT signing.
 */
@Component
public class JwtSecretValidator {

    private final String jwtSecret;

    public JwtSecretValidator(@Value("${app.jwt.secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @PostConstruct
    public void validate() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "app.jwt.secret must be configured with at least 64 characters. " +
                "Set the JWT_SECRET environment variable.");
        }
        if (jwtSecret.length() < 64) {
            throw new IllegalStateException(
                "app.jwt.secret must be configured with at least 64 characters " +
                "(currently " + jwtSecret.length() + " characters). " +
                "Use a cryptographically random value.");
        }
    }
}
