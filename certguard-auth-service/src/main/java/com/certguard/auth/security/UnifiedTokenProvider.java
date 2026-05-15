package com.certguard.auth.security;

import com.certguard.auth.dto.response.ValidateResponse;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and validates the unified JWT consumed by both the Java app and the PHP app.
 *
 * Token claims:
 *   sub        — user UUID
 *   provider   — google | microsoft | email
 *   email      — verified email address
 *   name       — display name
 *   providerIds — {"google":"<sub>", "microsoft":"<oid>"} — only the active provider populated
 *   iss        — certguard-auth
 *   aud        — certguard-apps
 *   iat / exp  — standard
 *   jti        — session UUID (enables revocation via session table lookup)
 */
@Slf4j
@Component
public class UnifiedTokenProvider {

    private static final String ISSUER   = "certguard-auth";
    private static final String AUDIENCE = "certguard-apps";

    @Value("${auth.jwt.secret}")
    private String secret;

    @Value("${auth.jwt.expiration-seconds:28800}")
    private long expirationSeconds;

    @PostConstruct
    public void validate() {
        if (secret == null || secret.length() < 64) {
            throw new IllegalStateException("auth.jwt.secret must be at least 64 characters");
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(UUID userId, String provider, String email, String name,
                        String providerUserId, UUID sessionId) {
        Instant now    = Instant.now();
        Instant expiry = now.plusSeconds(expirationSeconds);

        Map<String, String> providerIds = providerUserId != null
                ? Map.of(provider, providerUserId)
                : Map.of();

        return Jwts.builder()
                .id(sessionId.toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(userId.toString())
                .claim("provider", provider)
                .claim("email", email)
                .claim("name", name)
                .claim("providerIds", providerIds)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validate(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public ValidateResponse toValidateResponse(Claims claims) {
        return new ValidateResponse(
                true,
                claims.getSubject(),
                claims.get("provider", String.class),
                claims.get("email", String.class),
                claims.get("name", String.class),
                claims.get("providerIds", Map.class),
                claims.getExpiration().toInstant().getEpochSecond(),
                claims.getIssuedAt().toInstant().getEpochSecond(),
                claims.getIssuer()
        );
    }

    public long expirationSeconds() {
        return expirationSeconds;
    }
}
