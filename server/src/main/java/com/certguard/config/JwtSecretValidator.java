package com.certguard.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Fails fast on startup if JWT_SECRET is missing or shorter than 32 bytes.
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
                "JWT_SECRET environment variable is required but not set. " +
                "Provide a secret of at least 32 bytes.");
        }
        int byteLength = jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < 32) {
            throw new IllegalStateException(
                "JWT_SECRET is too short (" + byteLength + " bytes). " +
                "It must be at least 32 bytes (256 bits).");
        }
    }
}
