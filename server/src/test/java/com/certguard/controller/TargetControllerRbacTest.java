package com.certguard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import com.certguard.DockerAvailableCondition;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack RBAC integration tests using MockMvc.
 *
 * Strategy:
 * - Boot the full application context against a Testcontainers Postgres instance.
 * - Use the dev-token endpoint (app.dev-mode=true) to obtain JWTs for each role.
 * - Assert that ADMIN can list/create targets and that unauthenticated requests
 *   receive 401. Tenant isolation is verified by checking 404 when one org's
 *   user tries to access another org's resource.
 *
 * Spring Boot 4.0 note: @AutoConfigureMockMvc was removed; MockMvc is built
 * manually from the WebApplicationContext including the security filter chain.
 *
 * Test naming convention: methodUnderTest_condition_expectedOutcome
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("tctest")
class TargetControllerRbacTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_rbac_test")
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
    @Autowired ObjectMapper objectMapper;

    MockMvc mockMvc;
    String adminToken;
    String viewerToken;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        adminToken  = issueDevToken("admin-rbac@certguard.local",  "ADMIN");
        viewerToken = issueDevToken("viewer-rbac@certguard.local", "VIEWER");
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

    // ── Unauthenticated ───────────────────────────────────────────────────────

    @Nested
    class Unauthenticated {

        @Test
        void listTargets_whenNoToken_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/targets"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void createTarget_whenNoToken_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/targets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"host\":\"x.com\",\"port\":443}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── ADMIN role ────────────────────────────────────────────────────────────

    @Nested
    class AdminRole {

        @Test
        void listTargets_whenAdmin_returns200() throws Exception {
            mockMvc.perform(get("/api/v1/targets")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray());
        }

        @Test
        void createTarget_whenAdmin_returns201() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "host", "admin-created.example.com",
                    "port", 443
            ));
            mockMvc.perform(post("/api/v1/targets")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.host").value("admin-created.example.com"));
        }

        @Test
        void createTarget_whenAdminAndMissingHost_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("port", 443));
            mockMvc.perform(post("/api/v1/targets")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── VIEWER role ───────────────────────────────────────────────────────────

    @Nested
    class ViewerRole {

        @Test
        void listTargets_whenViewer_returns200() throws Exception {
            mockMvc.perform(get("/api/v1/targets")
                            .header("Authorization", "Bearer " + viewerToken))
                    .andExpect(status().isOk());
        }
    }

    // ── Tenant isolation ──────────────────────────────────────────────────────

    @Nested
    class TenantIsolation {

        @Test
        void scanStatus_whenTargetBelongsToOtherOrg_returns404() throws Exception {
            // Create a target in admin's org
            String body = objectMapper.writeValueAsString(Map.of(
                    "host", "org-scoped.example.com",
                    "port", 443
            ));
            MvcResult created = mockMvc.perform(post("/api/v1/targets")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andReturn();

            Map<?, ?> createdBody = objectMapper.readValue(
                    created.getResponse().getContentAsString(), Map.class);
            String targetId = (String) createdBody.get("id");

            // Viewer (different org) trying to access admin's target
            // should get 404 because tenant filter scopes by orgId
            mockMvc.perform(get("/api/v1/targets/" + targetId + "/scan-status")
                            .header("Authorization", "Bearer " + viewerToken))
                    .andExpect(status().isNotFound());
        }
    }
}
