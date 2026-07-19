package com.certguard.controller;

import com.certguard.entity.NotificationOutbox;
import com.certguard.entity.Organization;
import com.certguard.enums.OrgType;
import com.certguard.enums.OutboxStatus;
import com.certguard.repository.NotificationOutboxRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.certguard.DockerAvailableCondition;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack RBAC + tenant isolation tests for
 * {@code GET /api/v1/organizations/{orgId}/notifications/delivery-status}.
 *
 * <p>Tokens are minted directly via {@link JwtTokenProvider} (rather than the
 * {@code /auth/dev-token} endpoint, which provisions a brand-new org per unique email — this
 * suite needs two *different roles in the same org* to exercise {@code lastError} redaction).
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("tctest")
class NotificationDeliveryStatusControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_delivery_status_rbac_test")
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
        registry.add("spring.rabbitmq.host",                () -> "localhost");
        registry.add("spring.rabbitmq.port",                () -> "5672");
    }

    @Autowired WebApplicationContext wac;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired NotificationOutboxRepository outboxRepository;

    MockMvc mockMvc;
    Organization orgA;
    Organization orgB;
    String adminTokenOrgA;
    String viewerTokenOrgA;
    String adminTokenOrgB;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        orgA = organizationRepository.save(Organization.builder()
                .name("Org A")
                .slug("org-a-" + UUID.randomUUID().toString().substring(0, 8))
                .orgType(OrgType.SINGLE)
                .build());
        orgB = organizationRepository.save(Organization.builder()
                .name("Org B")
                .slug("org-b-" + UUID.randomUUID().toString().substring(0, 8))
                .orgType(OrgType.SINGLE)
                .build());

        adminTokenOrgA  = jwtTokenProvider.generateToken(UUID.randomUUID(), orgA.getId(), "admin-a@certguard.local",  false, "ADMIN");
        viewerTokenOrgA = jwtTokenProvider.generateToken(UUID.randomUUID(), orgA.getId(), "viewer-a@certguard.local", false, "VIEWER");
        adminTokenOrgB  = jwtTokenProvider.generateToken(UUID.randomUUID(), orgB.getId(), "admin-b@certguard.local",  false, "ADMIN");

        // Degrade org A: one retrying PENDING row and one terminal FAILED row.
        outboxRepository.save(NotificationOutbox.builder()
                .orgId(orgA.getId())
                .toAddress("ops@example.com")
                .subject("subj")
                .templateName("expiry-warning")
                .templateVars(Map.of("host", "example.com"))
                .status(OutboxStatus.PENDING)
                .attempts(2)
                .lastError("SMTP relay unreachable")
                .build());
        outboxRepository.save(NotificationOutbox.builder()
                .orgId(orgA.getId())
                .toAddress("ops2@example.com")
                .subject("subj")
                .templateName("revocation-alert")
                .templateVars(Map.of("host", "example.com"))
                .status(OutboxStatus.FAILED)
                .attempts(200)
                .lastError("attempt cap exhausted")
                .build());
    }

    @Nested
    class Unauthenticated {

        @Test
        void deliveryStatus_whenNoToken_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/notifications/delivery-status"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class AnyMemberCanRead {

        @Test
        void deliveryStatus_whenViewer_returns200AndDegraded() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/notifications/delivery-status")
                            .header("Authorization", "Bearer " + viewerTokenOrgA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.degraded").value(true))
                    .andExpect(jsonPath("$.queuedCount").value(1))
                    .andExpect(jsonPath("$.failedCount").value(1));
        }

        @Test
        void deliveryStatus_whenAdmin_returns200AndDegraded() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/notifications/delivery-status")
                            .header("Authorization", "Bearer " + adminTokenOrgA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.degraded").value(true));
        }
    }

    @Nested
    class LastErrorRedaction {

        @Test
        void viewer_lastErrorIsNull() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/notifications/delivery-status")
                            .header("Authorization", "Bearer " + viewerTokenOrgA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastError").doesNotExist());
        }

        @Test
        void admin_lastErrorIsPresent() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/notifications/delivery-status")
                            .header("Authorization", "Bearer " + adminTokenOrgA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastError").isNotEmpty());
        }
    }

    @Nested
    class NoRecipientLeak {

        @Test
        void adminResponse_neverContainsToAddress() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/notifications/delivery-status")
                            .header("Authorization", "Bearer " + adminTokenOrgA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.toAddress").doesNotExist())
                    .andExpect(jsonPath("$.recipient").doesNotExist())
                    .andExpect(jsonPath("$.email").doesNotExist());
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void deliveryStatus_whenCallerFromOtherOrg_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/notifications/delivery-status")
                            .header("Authorization", "Bearer " + adminTokenOrgB))
                    .andExpect(status().isForbidden());
        }

        @Test
        void deliveryStatus_orgBIsHealthy_unaffectedByOrgAOutage() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/" + orgB.getId() + "/notifications/delivery-status")
                            .header("Authorization", "Bearer " + adminTokenOrgB))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.degraded").value(false))
                    .andExpect(jsonPath("$.queuedCount").value(0))
                    .andExpect(jsonPath("$.failedCount").value(0));
        }
    }
}
