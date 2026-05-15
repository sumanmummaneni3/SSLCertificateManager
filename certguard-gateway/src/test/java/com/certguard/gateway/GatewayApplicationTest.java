package com.certguard.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test: verifies the Spring application context loads successfully.
 *
 * <p>GATEWAY_DEV_MODE is set to true so that the CORS wildcard guard does not
 * throw during context initialization in CI (no explicit origins configured).
 * AUTH_JWT_SECRET is supplied inline (64+ characters) to satisfy the PostConstruct
 * validation in JwtValidationFilter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "gateway.jwt.secret=test-secret-value-that-is-at-least-64-chars-long-for-hs256-validation-ok",
        "gateway.dev-mode=true",
        "gateway.cors.allowed-origins=",
        // Disable Spring Cloud Gateway's default route lookup from config server
        "spring.cloud.config.enabled=false"
})
class GatewayApplicationTest {

    @Test
    void contextLoads() {
        // If the context fails to start, this test fails with a descriptive error.
    }
}
