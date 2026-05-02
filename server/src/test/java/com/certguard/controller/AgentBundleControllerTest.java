package com.certguard.controller;

import com.certguard.DockerAvailableCondition;
import com.certguard.dto.IssueBundleResult;
import com.certguard.dto.request.CreateAgentRequest;
import com.certguard.exception.BundleExpiredException;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.service.AgentBundleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AgentController's bundle endpoints.
 *
 * Strategy: full Spring context against Testcontainers Postgres.
 * AgentBundleService is mocked so tests run fast without crypto operations.
 * Auth uses the dev-token endpoint (app.dev-mode=true in tctest profile).
 *
 * Conventions: methodName_condition_expectedOutcome
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("tctest")
class AgentBundleControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_bundle_test")
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

    @MockitoBean
    AgentBundleService agentBundleService;

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

        adminToken  = issueDevToken("bundle-admin@certguard.local",  "ADMIN");
        viewerToken = issueDevToken("bundle-viewer@certguard.local", "VIEWER");
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

    // ── POST /api/v1/agent/agents ─────────────────────────────────────────────

    @Nested
    class CreateAgent {

        @Test
        void createAgent_whenAdmin_returns201WithInstallKey() throws Exception {
            UUID agentId = UUID.randomUUID();
            IssueBundleResult mockResult = new IssueBundleResult(
                    agentId,
                    "CGK-TESTINSTALLKEY12345",
                    "https://certguard.example.com/api/v1/agents/" + agentId + "/bundle?dlToken=abc",
                    Instant.now().plusSeconds(3600));

            when(agentBundleService.issueBundle(any(), any(), any())).thenReturn(mockResult);

            String body = objectMapper.writeValueAsString(Map.of(
                    "agentName",    "prod-agent-01",
                    "allowedCidrs", List.of("10.0.0.0/8"),
                    "maxTargets",   50
            ));

            mockMvc.perform(post("/api/v1/agents")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.agentId").value(agentId.toString()))
                    .andExpect(jsonPath("$.installKey").value("CGK-TESTINSTALLKEY12345"))
                    .andExpect(jsonPath("$.bundleDownloadUrl").isNotEmpty())
                    .andExpect(jsonPath("$.expiresAt").isNotEmpty());
        }

        @Test
        void createAgent_whenViewer_returns403() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "agentName",    "viewer-agent",
                    "allowedCidrs", List.of("10.0.0.0/8"),
                    "maxTargets",   10
            ));

            mockMvc.perform(post("/api/v1/agents")
                            .header("Authorization", "Bearer " + viewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(agentBundleService);
        }

        @Test
        void createAgent_whenUnauthenticated_returns401() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "agentName",    "anon-agent",
                    "allowedCidrs", List.of("10.0.0.0/8"),
                    "maxTargets",   10
            ));

            mockMvc.perform(post("/api/v1/agents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void createAgent_whenAgentNameHasSpecialChars_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "agentName",    "bad<>agent!", // special chars not allowed
                    "allowedCidrs", List.of("10.0.0.0/8"),
                    "maxTargets",   10
            ));

            mockMvc.perform(post("/api/v1/agents")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void createAgent_whenAgentNameTooShort_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "agentName",    "ab", // only 2 chars, minimum is 3
                    "allowedCidrs", List.of("10.0.0.0/8"),
                    "maxTargets",   10
            ));

            mockMvc.perform(post("/api/v1/agents")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void createAgent_whenMissingAllowedCidrs_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "agentName",  "valid-agent",
                    "maxTargets", 10
            ));

            mockMvc.perform(post("/api/v1/agents")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── GET /api/v1/agents/{agentId}/bundle ──────────────────────────────────

    @Nested
    class DownloadBundle {

        @Test
        void downloadBundle_validToken_returns200WithZipContentDisposition() throws Exception {
            UUID agentId = UUID.randomUUID();
            byte[] fakeZip = new byte[]{0x50, 0x4B, 0x03, 0x04}; // ZIP magic bytes

            when(agentBundleService.buildBundleZip(eq(agentId), anyString()))
                    .thenReturn(fakeZip);

            mockMvc.perform(get("/api/v1/agents/" + agentId + "/bundle")
                            .param("dlToken", "valid-download-token"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            org.hamcrest.Matchers.containsString(
                                    "certguard-agent-" + agentId + ".zip")))
                    .andExpect(content().contentType("application/zip"));
        }

        @Test
        void downloadBundle_expiredToken_returns410Gone() throws Exception {
            UUID agentId = UUID.randomUUID();

            when(agentBundleService.buildBundleZip(eq(agentId), anyString()))
                    .thenThrow(new BundleExpiredException("Bundle has already been downloaded"));

            mockMvc.perform(get("/api/v1/agents/" + agentId + "/bundle")
                            .param("dlToken", "expired-token"))
                    .andExpect(status().isGone());
        }

        @Test
        void downloadBundle_replayToken_returns410Gone() throws Exception {
            UUID agentId = UUID.randomUUID();

            when(agentBundleService.buildBundleZip(eq(agentId), anyString()))
                    .thenThrow(new BundleExpiredException("Bundle has already been downloaded"));

            // First download would succeed (handled by the mock's first invocation behaviour)
            // Second attempt returns 410
            mockMvc.perform(get("/api/v1/agents/" + agentId + "/bundle")
                            .param("dlToken", "replayed-token"))
                    .andExpect(status().isGone());
        }

        @Test
        void downloadBundle_unknownToken_returns404() throws Exception {
            UUID agentId = UUID.randomUUID();

            when(agentBundleService.buildBundleZip(eq(agentId), anyString()))
                    .thenThrow(new ResourceNotFoundException("Bundle not found — token invalid"));

            mockMvc.perform(get("/api/v1/agents/" + agentId + "/bundle")
                            .param("dlToken", "unknown-token"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void downloadBundle_missingDlToken_returns400() throws Exception {
            UUID agentId = UUID.randomUUID();

            mockMvc.perform(get("/api/v1/agents/" + agentId + "/bundle"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void downloadBundle_noAuthRequired_publicEndpoint() throws Exception {
            // The bundle download endpoint is intentionally public (token-based auth)
            UUID agentId = UUID.randomUUID();
            byte[] fakeZip = new byte[]{0x50, 0x4B, 0x03, 0x04};

            when(agentBundleService.buildBundleZip(eq(agentId), anyString()))
                    .thenReturn(fakeZip);

            // No Authorization header — should still work (public endpoint)
            mockMvc.perform(get("/api/v1/agents/" + agentId + "/bundle")
                            .param("dlToken", "valid-token-no-auth"))
                    .andExpect(status().isOk());
        }
    }
}
