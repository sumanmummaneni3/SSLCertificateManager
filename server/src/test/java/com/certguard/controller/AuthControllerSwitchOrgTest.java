package com.certguard.controller;

import com.certguard.DockerAvailableCondition;
import com.certguard.entity.Organization;
import com.certguard.entity.OrgMember;
import com.certguard.entity.RevokedToken;
import com.certguard.entity.User;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.OrgType;
import com.certguard.enums.UserRole;
import com.certguard.repository.OrgMemberRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.RevokedTokenRepository;
import com.certguard.repository.UserRepository;
import com.certguard.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RFC 0015 Phase 2 — server local/dev switch-org endpoint.
 *
 * <p>This endpoint mints an HS256 token which the gateway does NOT accept (see the
 * doc-comment on {@link AuthController#switchOrg}); it exists for local dev / testing
 * the bare-JWT fallback path in {@code JwtAuthenticationFilter}. These tests exercise
 * exactly that fallback: tokens are minted directly via {@link JwtTokenProvider}
 * (mirrors {@code NotificationDeliveryStatusControllerTest}'s pattern) and presented
 * with no gateway {@code X-CG-*} headers.
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("tctest")
class AuthControllerSwitchOrgTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_switch_org_test")
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
    @Autowired UserRepository userRepository;
    @Autowired OrgMemberRepository orgMemberRepository;
    @Autowired RevokedTokenRepository revokedTokenRepository;

    MockMvc mockMvc;
    Organization orgA;
    Organization orgB;
    User user;
    String tokenScopedToOrgA;

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

        user = userRepository.save(User.builder()
                .organization(orgA)
                .email("switcher-" + UUID.randomUUID() + "@example.com")
                .name("Switcher")
                .role(UserRole.MEMBER)
                .build());

        orgMemberRepository.save(OrgMember.builder()
                .organization(orgA).user(user).role(OrgMemberRole.ADMIN)
                .inviteStatus(InviteStatus.ACCEPTED)
                .build());

        tokenScopedToOrgA = jwtTokenProvider.generateToken(
                user.getId(), orgA.getId(), user.getEmail(), false, "ADMIN");
    }

    private String body(UUID orgId) {
        return "{\"orgId\":\"" + orgId + "\"}";
    }

    @Nested
    class ValidSwitch {

        @Test
        void switchOrg_validMembership_returnsNewTokenScopedToOrgB() throws Exception {
            orgMemberRepository.save(OrgMember.builder()
                    .organization(orgB).user(user).role(OrgMemberRole.ENGINEER)
                    .inviteStatus(InviteStatus.ACCEPTED)
                    .build());

            mockMvc.perform(post("/api/v1/auth/switch-org")
                            .header("Authorization", "Bearer " + tokenScopedToOrgA)
                            .contentType("application/json")
                            .content(body(orgB.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orgId").value(orgB.getId().toString()))
                    .andExpect(jsonPath("$.orgRole").value("ENGINEER"))
                    .andExpect(jsonPath("$.token").isNotEmpty());

            User reloaded = userRepository.findById(user.getId()).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(reloaded.getLastActiveOrgId())
                    .isEqualTo(orgB.getId());
        }
    }

    @Nested
    class NonMember {

        @Test
        void switchOrg_noMembershipInTargetOrg_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/auth/switch-org")
                            .header("Authorization", "Bearer " + tokenScopedToOrgA)
                            .contentType("application/json")
                            .content(body(orgB.getId())))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class RevokedMembership {

        @Test
        void switchOrg_revokedMembership_returns403() throws Exception {
            orgMemberRepository.save(OrgMember.builder()
                    .organization(orgB).user(user).role(OrgMemberRole.ENGINEER)
                    .inviteStatus(InviteStatus.ACCEPTED)
                    .revokedAt(Instant.now())
                    .build());

            mockMvc.perform(post("/api/v1/auth/switch-org")
                            .header("Authorization", "Bearer " + tokenScopedToOrgA)
                            .contentType("application/json")
                            .content(body(orgB.getId())))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class RevokedSession {

        @Test
        void switchOrg_revokedTokenForTargetOrg_returns403() throws Exception {
            orgMemberRepository.save(OrgMember.builder()
                    .organization(orgB).user(user).role(OrgMemberRole.ENGINEER)
                    .inviteStatus(InviteStatus.ACCEPTED)
                    .build());
            revokedTokenRepository.save(RevokedToken.of(
                    user.getId(), orgB.getId(), null, "offboarded",
                    Instant.now().plusSeconds(3600)));

            mockMvc.perform(post("/api/v1/auth/switch-org")
                            .header("Authorization", "Bearer " + tokenScopedToOrgA)
                            .contentType("application/json")
                            .content(body(orgB.getId())))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class PlatformAdmin {

        @Test
        void switchOrg_platformAdminCaller_rejected() throws Exception {
            orgMemberRepository.save(OrgMember.builder()
                    .organization(orgB).user(user).role(OrgMemberRole.ENGINEER)
                    .inviteStatus(InviteStatus.ACCEPTED)
                    .build());

            String adminToken = jwtTokenProvider.generateToken(
                    user.getId(), orgA.getId(), user.getEmail(), true, null);

            mockMvc.perform(post("/api/v1/auth/switch-org")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType("application/json")
                            .content(body(orgB.getId())))
                    .andExpect(status().is4xxClientError());
        }
    }
}
