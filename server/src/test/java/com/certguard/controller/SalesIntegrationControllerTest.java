package com.certguard.controller;

import com.certguard.DockerAvailableCondition;
import com.certguard.entity.Organization;
import com.certguard.entity.SalesApiKey;
import com.certguard.entity.Subscription;
import com.certguard.enums.SalesKeyStatus;
import com.certguard.enums.SubscriptionStatus;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.SalesApiKeyRepository;
import com.certguard.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies that {@link SalesIntegrationController} endpoints require authentication.
 * D3: verifies PENDING_ACTIVATION → SUSPENDED transition is accepted by the state machine.
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("tctest")
class SalesIntegrationControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_sales_ctrl_test")
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

    @Autowired WebApplicationContext wac;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired SalesApiKeyRepository salesApiKeyRepository;
    @Autowired BCryptPasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    MockMvc mockMvc;

    /** Plain-text sales key used across D3 tests. */
    private String plainSalesKey;
    private UUID salesKeyId;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Seed a sales API key once (idempotent — re-uses if already exists).
        if (salesKeyId == null) {
            plainSalesKey = "SLS-" + UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            SalesApiKey key = SalesApiKey.builder()
                    .label("d3-test-key-" + UUID.randomUUID())
                    .keyHash(passwordEncoder.encode(plainSalesKey))
                    .status(SalesKeyStatus.ACTIVE)
                    .build();
            key = salesApiKeyRepository.save(key);
            salesKeyId = key.getId();
        }
    }

    // ── Unauthenticated guard tests ────────────────────────────────────────

    @Test
    void ping_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/internal/v1/sales/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void orgs_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/internal/v1/sales/orgs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void orgDetail_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/internal/v1/sales/orgs/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isUnauthorized());
    }

    // ── D3: Rejection flow — PENDING_ACTIVATION → SUSPENDED ───────────────

    /**
     * D3 scenario:
     * 1. Create an org whose subscription is at PENDING_ACTIVATION.
     * 2. Set quota to 0 via PATCH .../quota — should succeed (quota update is independent).
     * 3. Set status to SUSPENDED via PATCH .../subscription — transition must be allowed.
     */
    @Test
    void d3_pendingActivationToSuspended_transitionSucceeds() throws Exception {
        // Arrange: org with PENDING_ACTIVATION subscription
        Organization org = organizationRepository.save(
                Organization.builder().name("D3 Test Org").build());
        Subscription sub = subscriptionRepository.save(
                Subscription.builder()
                        .organization(org)
                        .maxCertificateQuota(5)
                        .status(SubscriptionStatus.PENDING_ACTIVATION)
                        .build());

        UUID orgId = org.getId();

        // Step 2: PATCH quota → 0  (should be HTTP 200)
        String quotaBody = objectMapper.writeValueAsString(
                Map.of("maxCertificateQuota", 0));

        mockMvc.perform(patch("/api/internal/v1/sales/orgs/" + orgId + "/quota")
                        .header("X-Sales-Key-Id", salesKeyId.toString())
                        .header("X-Sales-Key",     plainSalesKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quotaBody))
                .andExpect(status().isOk());

        // Step 3: PATCH subscription status → SUSPENDED
        // PENDING_ACTIVATION → SUSPENDED must be in the allowed transition matrix.
        String statusBody = objectMapper.writeValueAsString(
                Map.of("status", "SUSPENDED", "reason", "D3 rejection flow test"));

        mockMvc.perform(patch("/api/internal/v1/sales/orgs/" + orgId + "/subscription")
                        .header("X-Sales-Key-Id", salesKeyId.toString())
                        .header("X-Sales-Key",     plainSalesKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }
}
