package com.certguard.gateway;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Smoke test: verifies the Spring application context loads successfully.
 *
 * <p>An in-process JDK {@link HttpServer} serves a minimal JWKS so that
 * {@code JwtValidationFilter#initKeys()} can fetch the RSA public key at startup.
 * GATEWAY_DEV_MODE is set to true so that the CORS wildcard guard does not throw
 * during context initialization in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTest {

    private static HttpServer jwksServer;
    private static int jwksPort;

    @BeforeAll
    static void startJwksServer() throws Exception {
        RSAKey rsaKey = new RSAKeyGenerator(2048)
                .keyID("smoke-test-key")
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

    @Test
    void contextLoads() {
        // If the context fails to start, this test fails with a descriptive error.
    }
}
