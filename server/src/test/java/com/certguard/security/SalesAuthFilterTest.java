package com.certguard.security;

import com.certguard.DockerAvailableCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies that {@link SalesAuthFilter} correctly gates the internal sales API:
 * <ul>
 *   <li>Requests with no credentials return HTTP 401.</li>
 *   <li>Non-sales paths are not filtered (no auth required from this filter).</li>
 * </ul>
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("tctest")
class SalesAuthFilterTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_sales_filter_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",             postgres::getJdbcUrl);
        registry.add("spring.datasource.username",         postgres::getUsername);
        registry.add("spring.datasource.password",         postgres::getPassword);
        registry.add("spring.flyway.enabled",              () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto",      () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.type.preferred_enum_jdbc_type", () -> "VARCHAR");
        registry.add("app.dev-mode",                       () -> "true");
        registry.add("server.ssl.enabled",                 () -> "false");
        registry.add("spring.rabbitmq.host",               () -> "localhost");
        registry.add("spring.rabbitmq.port",               () -> "5672");
    }

    @Autowired
    WebApplicationContext wac;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void salesPing_withNoCredentials_returns401() throws Exception {
        mockMvc.perform(get("/api/internal/v1/sales/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void salesOrgs_withNoCredentials_returns401() throws Exception {
        mockMvc.perform(get("/api/internal/v1/sales/orgs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void salesPing_withInvalidKeyId_returns401() throws Exception {
        mockMvc.perform(get("/api/internal/v1/sales/ping")
                        .header("X-Sales-Key-Id", "not-a-uuid")
                        .header("X-Sales-Key",     "SLS-somekey"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void salesPing_withUnknownKeyId_returns401() throws Exception {
        // Valid UUID format but key does not exist
        mockMvc.perform(get("/api/internal/v1/sales/ping")
                        .header("X-Sales-Key-Id", "00000000-0000-0000-0000-000000000000")
                        .header("X-Sales-Key",     "SLS-somekey"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonSalesPath_isNotBlockedByThisFilter() throws Exception {
        // /api/v1/auth/config is public — the sales filter should not touch it.
        // We just confirm it does not return 401 from the sales filter.
        mockMvc.perform(get("/api/v1/auth/config"))
                .andExpect(status().isOk());
    }
}
