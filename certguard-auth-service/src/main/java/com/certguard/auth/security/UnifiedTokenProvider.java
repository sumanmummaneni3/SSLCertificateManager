package com.certguard.auth.security;

import com.certguard.auth.dto.response.ValidateResponse;
import com.certguard.auth.service.OrgContextRecord;
import io.jsonwebtoken.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

/**
 * Issues and validates the unified RS256 JWT consumed by the gateway and downstream services.
 *
 * Token claims:
 *   sub           — user UUID
 *   provider      — google | microsoft | email
 *   email         — verified email address
 *   orgId         — organization UUID (empty string when not yet assigned)
 *   orgRole       — member | admin | owner (empty string when not yet assigned)
 *   platformAdmin — boolean
 *   iss           — certguard-cloud
 *   aud           — [certguard-ui, certguard-apps]
 *   iat / exp     — standard; platform-admin tokens carry a far-future exp (~100 years)
 *   jti           — session UUID (enables revocation via session table lookup)
 *
 * TTL policy (confirmed by product owner):
 *   - Normal users:      absolute TTL = configurable (default 24 h, {@code auth.jwt.expiration-ms}).
 *   - Platform admins:   non-expiring — exp is set 100 years in the future so the token and
 *                        session row remain structurally identical; no schema change required.
 */
@Slf4j
@Component
public class UnifiedTokenProvider {

    public static final String ISSUER = "certguard-cloud";
    public static final List<String> AUDIENCE = List.of("certguard-ui", "certguard-apps");

    /**
     * Sentinel TTL used for platform-admin tokens: 100 years expressed in milliseconds.
     * Chosen over omitting {@code exp} to keep {@link #toValidateResponse} and the session
     * expiry check in TokenService NPE-free without a DB schema change.
     */
    public static final long ADMIN_NON_EXPIRING_TTL_MS = 100L * 365 * 24 * 60 * 60 * 1000;

    @Value("${auth.jwt.private-key-path:/opt/certguard-auth/certs/jwt-private.pem}")
    private String privateKeyPath;

    @Value("${auth.jwt.public-key-path:/opt/certguard-auth/certs/jwt-public.pem}")
    private String publicKeyPath;

    @Value("${auth.jwt.expiration-ms:86400000}")
    private long expirationMs;

    private PrivateKey privateKey;
    private RSAPublicKey publicKey;

    @PostConstruct
    public void initKeys() {
        Path privPath = Path.of(privateKeyPath);
        Path pubPath  = Path.of(publicKeyPath);

        if (Files.exists(privPath) && Files.exists(pubPath)) {
            log.info("Loading existing RSA key pair from {} / {}", privateKeyPath, publicKeyPath);
            this.privateKey = loadPrivateKey(privPath);
            this.publicKey  = (RSAPublicKey) loadPublicKey(pubPath);
        } else {
            log.info("Generating new RSA-2048 key pair and persisting to {} / {}", privateKeyPath, publicKeyPath);
            KeyPair pair = generateKeyPair();
            this.privateKey = pair.getPrivate();
            this.publicKey  = (RSAPublicKey) pair.getPublic();
            persistKeys(privPath, pubPath, pair);
        }
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    public String issue(UUID userId, String provider, String email, String name,
                        String providerUserId, UUID sessionId, OrgContextRecord ctx) {
        Instant now           = Instant.now();
        boolean isPlatformAdmin = ctx != null && ctx.platformAdmin();
        long    ttlMs         = isPlatformAdmin ? ADMIN_NON_EXPIRING_TTL_MS : expirationMs;
        Instant expiry        = now.plusMillis(ttlMs);

        return Jwts.builder()
                .id(sessionId.toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(ctx != null && ctx.userId() != null ? ctx.userId().toString() : userId.toString())
                .claim("provider", provider)
                .claim("email", email)
                .claim("name", name)
                .claim("orgId",         ctx != null && ctx.orgId() != null ? ctx.orgId().toString() : "")
                .claim("orgRole",       ctx != null && ctx.orgRole() != null ? ctx.orgRole() : "")
                .claim("platformAdmin", isPlatformAdmin)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(ISSUER)
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

    /** Returns the normal-user token TTL in seconds (24 h by default). */
    public long expirationSeconds() {
        return expirationMs / 1000;
    }

    /**
     * Returns the effective token TTL in seconds for the given org context.
     * Platform-admin contexts yield {@link #ADMIN_NON_EXPIRING_TTL_MS} / 1000;
     * all others yield the configured normal-user TTL.
     */
    public long expirationSeconds(OrgContextRecord ctx) {
        if (ctx != null && ctx.platformAdmin()) {
            return ADMIN_NON_EXPIRING_TTL_MS / 1000;
        }
        return expirationMs / 1000;
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation failed", e);
        }
    }

    private void persistKeys(Path privPath, Path pubPath, KeyPair pair) {
        try {
            Files.createDirectories(privPath.getParent());
            Files.writeString(privPath, toPem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.writeString(pubPath,  toPem("PUBLIC KEY",  pair.getPublic().getEncoded()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist RSA key pair", e);
        }
    }

    private PrivateKey loadPrivateKey(Path path) {
        try {
            byte[] der = decodePem(Files.readString(path));
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load private key from " + path, e);
        }
    }

    private PublicKey loadPublicKey(Path path) {
        try {
            byte[] der = decodePem(Files.readString(path));
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load public key from " + path, e);
        }
    }

    private String toPem(String type, byte[] encoded) {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    private byte[] decodePem(String pem) {
        String stripped = pem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(stripped);
    }
}
