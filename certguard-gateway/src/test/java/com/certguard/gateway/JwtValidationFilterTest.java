package com.certguard.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Integration tests for {@link com.certguard.gateway.security.JwtValidationFilter}.
 *
 * <p>Uses a random port SpringBootTest with a WebTestClient. Upstream routes target
 * localhost so they will fail to connect, but the gateway's own filter logic
 * (401 on missing/invalid/expired tokens) is exercised before any upstream call.
 *
 * <p>For the "valid token injects headers" test, the request to /api/v1/certs will
 * get a 503/502 because no upstream is running — that is expected and acceptable for
 * a unit-level filter test. We assert only on the absence of a 401 (meaning the filter
 * passed the request forward rather than rejecting it) by checking that the X-CG-User-Id
 * header was injected. However, since WebTestClient only sees response headers, we use
 * a mock approach: assert the filter does NOT return 401 for a valid token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "gateway.jwt.secret=test-secret-value-that-is-at-least-64-chars-long-for-hs256-validation-ok",
        "gateway.dev-mode=true",
        "gateway.cors.allowed-origins=",
        "spring.cloud.config.enabled=false"
})
class JwtValidationFilterTest {

    private static final String JWT_SECRET =
            "test-secret-value-that-is-at-least-64-chars-long-for-hs256-validation-ok";
    private static final String ISSUER   = "certguard-auth";
    private static final String AUDIENCE = "certguard-apps";

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // ── Test helpers ───────────────────────────────────────────────────────────

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    /** Builds a valid, non-expired JWT matching the UnifiedTokenProvider contract. */
    private String validToken() {
        Instant now    = Instant.now();
        Instant expiry = now.plusSeconds(3600);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(UUID.randomUUID().toString())
                .claim("provider", "email")
                .claim("email", "user@example.com")
                .claim("name", "Test User")
                .claim("orgId",  UUID.randomUUID().toString())
                .claim("orgRole", "VIEWER")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey())
                .compact();
    }

    /** Builds a token that expired 1 hour ago. */
    private String expiredToken() {
        Instant past = Instant.now().minusSeconds(7200);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(UUID.randomUUID().toString())
                .claim("provider", "email")
                .claim("email", "old@example.com")
                .claim("name", "Old User")
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plusSeconds(1800)))
                .signWith(signingKey())
                .compact();
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * Unauthenticated request to a protected path must return 401 with
     * application/problem+json content-type.
     */
    @Test
    void unauthenticatedRequestToProtectedPath_returns401ProblemDetail() {
        webTestClient.get()
                .uri("/api/v1/certs")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.detail").exists();
    }

    /**
     * An expired JWT must return 401.
     */
    @Test
    void expiredToken_returns401() {
        webTestClient.get()
                .uri("/api/v1/certs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    /**
     * A request to /api/auth/** must be forwarded without a JWT check.
     * The upstream is not running so we expect a 503/502 — not a 401.
     * A 401 from our own filter would be wrong.
     */
    @Test
    void requestToAuthPath_forwardsWithoutJwtCheck() {
        webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"user@example.com\",\"password\":\"pass\"}")
                .exchange()
                // Should NOT be 401 (filter bypass); upstream is unreachable so 502/503 is fine.
                .expectStatus().value(status ->
                        org.junit.jupiter.api.Assertions.assertNotEquals(
                                HttpStatus.UNAUTHORIZED.value(), status,
                                "Expected /api/auth/** to bypass JWT filter but got 401"));
    }

    /**
     * A valid JWT should not produce a 401; the filter forwards the request downstream.
     * Since no upstream is running the response will be 503/502, which confirms the
     * filter accepted the token and let the request through.
     */
    @Test
    void validToken_doesNotReturn401() {
        webTestClient.get()
                .uri("/api/v1/certs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                .exchange()
                .expectStatus().value(status ->
                        org.junit.jupiter.api.Assertions.assertNotEquals(
                                HttpStatus.UNAUTHORIZED.value(), status,
                                "Expected valid JWT to pass filter but got 401"));
    }
}
