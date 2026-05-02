package com.certguard.security;

import com.certguard.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms:28800000}")
    private long expirationMs;

    @PostConstruct
    public void init() {
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.length() < 64) {
            throw new IllegalStateException(
                "app.jwt.secret must be configured with at least 64 characters");
        }
    }

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(User user) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer("certguard-cloud")
                .audience().add("certguard-ui").and()
                .subject(user.getId().toString())
                .claim("orgId", user.getOrganization().getId().toString())
                .claim("email", user.getEmail())
                .claim("role",  user.getRole().name())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getKey())
                .compact();
    }

    public String generateToken(UUID userId, UUID orgId, String email, String role) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer("certguard-cloud")
                .audience().add("certguard-ui").and()
                .subject(userId.toString())
                .claim("orgId", orgId.toString())
                .claim("email", email)
                .claim("role",  role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getKey())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .requireIssuer("certguard-cloud")
                .requireAudience("certguard-ui")
                .build()
                .parseSignedClaims(token).getPayload();
    }

    public boolean validateToken(String token) {
        try { parseToken(token); return true; }
        catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT: {}", e.getMessage());
            return false;
        }
    }
}
