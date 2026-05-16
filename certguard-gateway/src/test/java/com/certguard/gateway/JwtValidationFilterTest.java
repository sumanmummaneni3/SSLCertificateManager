package com.certguard.gateway;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Integration tests for {@link com.certguard.gateway.security.JwtValidationFilter}.
 *
 * <p>Spins up an in-process JDK {@link HttpServer} that serves a JWKS containing
 * a generated RSA-2048 key pair so the filter can fetch the public key at startup.
 * Tokens are signed with the corresponding private key.
 *
 * <p>Upstream routes target localhost so they will fail to connect, but the gateway's
 * own filter logic (401 on missing/invalid/expired tokens) is exercised before any
 * upstream call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtValidationFilterTest {

    private static RSAKey rsaKey;
    private static HttpServer jwksServer;
    private static int jwksPort;

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeAll
    static void startJwksServer() throws Exception {
        rsaKey = new RSAKeyGenerator(2048)
                .keyID("test-key-1")
                .generate();

        JWKSet jwkSet = new JWKSet(rsaKey.toPublicJWK());
        byte[] jwksBytes = jwkSet.toString().getBytes(StandardCharsets.UTF_8);

        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/api/auth/.well-known/jwks.json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwksBytes.length);
            exchange.getResponseBody().write(jwksBytes);
            exchange.getResponseBody().close();
        });
        jwksServer.start();
        jwksPort = jwksServer.getAddress().getPort();
    }

    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.jwt.jwks-uri",
                () -> "http://127.0.0.1:" + jwksPort + "/api/auth/.well-known/jwks.json");
        registry.add("gateway.jwt.issuer", () -> "certguard-cloud");
        registry.add("gateway.jwt.audience", () -> "certguard-ui");
        registry.add("gateway.dev-mode", () -> "true");
        registry.add("gateway.cors.allowed-origins", () -> "");
    }

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // ── Test helpers ───────────────────────────────────────────────────────────

    /** Builds a valid, non-expired RS256 JWT. */
    private String validToken() throws Exception {
        Instant now    = Instant.now();
        Instant expiry = now.plusSeconds(3600);
        RSAPrivateKey privateKey = rsaKey.toRSAPrivateKey();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer("certguard-cloud")
                .audience().add("certguard-ui").and()
                .subject(UUID.randomUUID().toString())
                .claim("provider", "email")
                .claim("email", "user@example.com")
                .claim("name", "Test User")
                .claim("orgId",  UUID.randomUUID().toString())
                .claim("orgRole", "VIEWER")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(privateKey)
                .compact();
    }

    /** Builds a token that expired 1 hour ago. */
    private String expiredToken() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        RSAPrivateKey privateKey = rsaKey.toRSAPrivateKey();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer("certguard-cloud")
                .audience().add("certguard-ui").and()
                .subject(UUID.randomUUID().toString())
                .claim("provider", "email")
                .claim("email", "old@example.com")
                .claim("name", "Old User")
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plusSeconds(1800)))
                .signWith(privateKey)
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
    void expiredToken_returns401() throws Exception {
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
    void validToken_doesNotReturn401() throws Exception {
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
