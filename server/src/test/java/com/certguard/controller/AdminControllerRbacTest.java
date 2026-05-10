package com.certguard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.certguard.DockerAvailableCondition;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RBAC integration tests for AdminController (/api/v1/admin/*).
 *
 * Tests:
 * 1. Non-PLATFORM_ADMIN roles (VIEWER, ADMIN, ENGINEER) get 403 from GET /api/v1/admin/orgs.
 * 2. PLATFORM_ADMIN without X-Acting-As-Org gets 200 from GET /api/v1/admin/orgs.
 * 3. PLATFORM_ADMIN with X-Acting-As-Org doing a POST without X-Acting-As-Reason gets 400.
 * 4. PLATFORM_ADMIN with X-Acting-As-Org and X-Acting-As-Reason doing a POST passes the filter (status != 400).
 *
 * Uses the dev-token endpoint (app.dev-mode=true) for JWT issuance and a
 * Testcontainers Postgres instance with Hibernate create-drop so no Flyway is needed.
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("tctest")
class AdminControllerRbacTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_admin_rbac_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",             postgres::getJdbcUrl);
        registry.add("spring.datasource.username",         postgres::getUsername);
        registry.add("spring.datasource.password",         postgres::getPassword);
        registry.add("spring.flyway.enabled",              () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto",      () -> "create-drop");
        // Enum columns must map as VARCHAR in create-drop mode (no named pg ENUM)
        registry.add("spring.jpa.properties.hibernate.type.preferred_enum_jdbc_type", () -> "VARCHAR");
        registry.add("app.dev-mode",                       () -> "true");
        registry.add("server.ssl.enabled",                 () -> "false");
        registry.add("spring.rabbitmq.host",               () -> "localhost");
        registry.add("spring.rabbitmq.port",               () -> "5672");
    }

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper objectMapper;

    MockMvc mockMvc;

    // Tokens issued per test via dev-token endpoint
    String viewerToken;
    String adminToken;
    String engineerToken;
    String platformAdminToken;
    String platformAdminOrgId;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        viewerToken       = issueDevToken("viewer-admin-rbac@certguard.local",   "VIEWER");
        adminToken        = issueDevToken("admin-admin-rbac@certguard.local",     "ADMIN");
        engineerToken     = issueDevToken("engineer-admin-rbac@certguard.local",  "MEMBER");
        platformAdminToken = issueDevToken("platform-admin-rbac@certguard.local", "PLATFORM_ADMIN");

        // Also capture the ADMIN user's org so we can send it as X-Acting-As-Org
        MvcResult adminResult = mockMvc.perform(
                post("/api/v1/auth/dev-token")
                        .param("email", "admin-admin-rbac@certguard.local")
                        .param("role",  "ADMIN"))
                .andReturn();
        Map<?, ?> adminBody = objectMapper.readValue(
                adminResult.getResponse().getContentAsString(), Map.class);
        platformAdminOrgId = adminBody.get("orgId").toString();
    }

    private String issueDevToken(String email, String role) throws Exception {
        MvcResult result = mockMvc.perform(
                post("/api/v1/auth/dev-token")
                        .param("email", email)
                        .param("role",  role))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("token");
    }

    // ── Non-PLATFORM_ADMIN roles must get 403 ────────────────────────────────

    @Nested
    class NonPlatformAdminRoles {

        @Test
        void listOrgs_asViewer_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/admin/orgs")
                            .header("Authorization", "Bearer " + viewerToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        void listOrgs_asAdmin_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/admin/orgs")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        void listOrgs_asEngineer_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/admin/orgs")
                            .header("Authorization", "Bearer " + engineerToken))
                    .andExpect(status().isForbidden());
        }
    }

    // ── PLATFORM_ADMIN without X-Acting-As-Org ───────────────────────────────

    @Nested
    class PlatformAdminDirect {

        @Test
        void listOrgs_asPlatformAdmin_returns200() throws Exception {
            mockMvc.perform(get("/api/v1/admin/orgs")
                            .header("Authorization", "Bearer " + platformAdminToken))
                    .andExpect(status().isOk());
        }
    }

    // ── X-Acting-As-Org without reason on write ───────────────────────────────

    @Nested
    class ActingAsOrgValidation {

        @Test
        void postWithActingAsOrgAndNoReason_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/targets")
                            .header("Authorization",  "Bearer " + platformAdminToken)
                            .header("X-Acting-As-Org", platformAdminOrgId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"host\":\"test.example.com\",\"port\":443}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Missing reason"));
        }

        @Test
        void postWithActingAsOrgAndReason_passesFilter() throws Exception {
            // The request gets past the filter (status is not 400 due to missing reason).
            // We target a well-known endpoint; the result may be 201 (created) or some other
            // non-400 status — both indicate the filter allowed it through.
            MvcResult result = mockMvc.perform(post("/api/v1/targets")
                            .header("Authorization",   "Bearer " + platformAdminToken)
                            .header("X-Acting-As-Org", platformAdminOrgId)
                            .header("X-Acting-As-Reason", "integration-test impersonation")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"host\":\"reason-provided.example.com\",\"port\":443}"))
                    .andReturn();

            int status = result.getResponse().getStatus();
            // Status 400 would mean the filter rejected it for missing reason; anything else means it passed.
            org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(400);
        }
    }
}
