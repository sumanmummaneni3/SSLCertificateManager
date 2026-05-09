package com.certguard.security;

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

    /**
     * Primary token generator. Callers must resolve the orgRole from OrgMember before calling.
     * platformAdmin=true implies orgRole should be null (platform admins are not org members).
     */
    public String generateToken(UUID userId, UUID orgId, String email,
                                 boolean platformAdmin, String orgRole) {
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer("certguard-cloud")
                .audience().add("certguard-ui").and()
                .subject(userId.toString())
                .claim("orgId", orgId.toString())
                .claim("email", email)
                .claim("platformAdmin", platformAdmin)
                .claim("orgRole", orgRole)
                // legacy role claim — kept for backward compat during token expiry window
                .claim("role", platformAdmin ? "PLATFORM_ADMIN" : (orgRole != null ? orgRole : "VIEWER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs));
        return builder.signWith(getKey()).compact();
    }

    /** Legacy overload used only during the transition period. Remove after V20 migration. */
    public String generateToken(UUID userId, UUID orgId, String email, String role) {
        boolean isPlatformAdmin = "PLATFORM_ADMIN".equals(role);
        String orgRole = isPlatformAdmin ? null : role;
        return generateToken(userId, orgId, email, isPlatformAdmin, orgRole);
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
