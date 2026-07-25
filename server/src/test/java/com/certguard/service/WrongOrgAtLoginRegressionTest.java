package com.certguard.service;

import com.certguard.DockerAvailableCondition;
import com.certguard.config.OAuth2AuthenticationSuccessHandler;
import com.certguard.dto.response.TargetResponse;
import com.certguard.entity.OrgMember;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.entity.User;
import com.certguard.enums.HostType;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.OrgType;
import com.certguard.enums.UserRole;
import com.certguard.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RFC 0015 Phase 1 regression test — reproduces the exact reported bug:
 * a user's fixed home org ({@code User.organization}) is org A, but the user
 * also holds a separate ACCEPTED membership in org B, and last logged in
 * (invite-accept) against org B — simulated here by stamping
 * {@code last_active_org_id = B} directly, per the Phase 1 scope (invite-accept
 * integration itself is covered separately by {@code InvitationServiceTest}).
 *
 * Before the fix, {@link OAuth2AuthenticationSuccessHandler} always minted the
 * JWT against {@code user.getOrganization().getId()} (org A), silently routing
 * the user back into the wrong org's data. This test drives the real handler
 * end-to-end and asserts the minted JWT's orgId is B, and that
 * {@link TargetService#listTargets} scoped to that JWT's org returns only B's
 * targets — never A's.
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest
@ActiveProfiles("tctest")
@Transactional
class WrongOrgAtLoginRegressionTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_wrong_org_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);
        registry.add("spring.flyway.enabled",       () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.type.preferred_enum_jdbc_type", () -> "VARCHAR");
        registry.add("server.ssl.enabled",          () -> "false");
        registry.add("spring.rabbitmq.host",        () -> "localhost");
    }

    @Autowired OAuth2AuthenticationSuccessHandler successHandler;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TargetService targetService;
    @PersistenceContext EntityManager em;

    Organization orgA; // home org
    Organization orgB; // invited-to org, last active
    User user;
    Target targetInA;
    Target targetInB;

    @BeforeEach
    void seed() {
        orgA = Organization.builder().name("Org A").slug("org-a-" + UUID.randomUUID().toString().substring(0, 8))
                .orgType(OrgType.SINGLE).build();
        orgB = Organization.builder().name("Org B").slug("org-b-" + UUID.randomUUID().toString().substring(0, 8))
                .orgType(OrgType.SINGLE).build();
        em.persist(orgA);
        em.persist(orgB);

        user = User.builder()
                .organization(orgA) // fixed home org — the bug always minted against this
                .email("invited-user@example.com")
                .name("Invited User")
                .role(UserRole.MEMBER)
                .googleSub("google-sub-invited-user")
                .build();
        em.persist(user);

        // Separate ACCEPTED membership in org B — simulates invite-accept into B having
        // already stamped last_active_org_id = B (see InvitationService.acceptInvite).
        OrgMember membershipInB = OrgMember.builder()
                .organization(orgB).user(user).role(OrgMemberRole.ENGINEER)
                .inviteStatus(InviteStatus.ACCEPTED)
                .build();
        em.persist(membershipInB);

        user.setLastActiveOrgId(orgB.getId());
        em.merge(user);

        targetInA = Target.builder()
                .organization(orgA).host("a-host.example.com").port(443)
                .hostType(HostType.DOMAIN).isPrivate(false).enabled(true).build();
        targetInB = Target.builder()
                .organization(orgB).host("b-host.example.com").port(443)
                .hostType(HostType.DOMAIN).isPrivate(false).enabled(true).build();
        em.persist(targetInA);
        em.persist(targetInB);

        em.flush();
    }

    @Test
    void loginMintsJwtScopedToLastActiveOrg_notHomeOrg_andTargetListingMatches() throws Exception {
        OidcUser oidcUser = new DefaultOidcUser(
                Collections.emptyList(),
                OidcIdToken.withTokenValue("id-token-value")
                        .claim("sub", "google-sub-invited-user")
                        .claim("email", user.getEmail())
                        .claim("name", user.getName())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build());

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(oidcUser);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, authentication);

        String redirectedUrl = response.getRedirectedUrl();
        assertThat(redirectedUrl).isNotNull().contains("token=");

        String token = extractQueryParam(redirectedUrl, "token");
        assertThat(token).isNotBlank();

        Claims claims = jwtTokenProvider.parseToken(token);
        String jwtOrgId = claims.get("orgId", String.class);

        // The core regression assertion: the minted JWT is scoped to org B
        // (last active / invited org), never org A (fixed home org).
        assertThat(jwtOrgId).isEqualTo(orgB.getId().toString());
        assertThat(jwtOrgId).isNotEqualTo(orgA.getId().toString());
        assertThat(claims.get("orgRole", String.class)).isEqualTo("ENGINEER");

        // Downstream: listing targets scoped to the JWT's org must return only
        // org B's targets, never org A's.
        Page<TargetResponse> page = targetService.listTargets(UUID.fromString(jwtOrgId), PageRequest.of(0, 10));
        List<String> hosts = page.getContent().stream().map(TargetResponse::getHost).collect(Collectors.toList());

        assertThat(hosts).containsExactly("b-host.example.com");
        assertThat(hosts).doesNotContain("a-host.example.com");
    }

    private static String extractQueryParam(String url, String name) throws Exception {
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            if (key.equals(name)) {
                String value = idx >= 0 ? pair.substring(idx + 1) : "";
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
