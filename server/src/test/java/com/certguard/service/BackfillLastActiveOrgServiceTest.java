package com.certguard.service;

import com.certguard.DockerAvailableCondition;
import com.certguard.entity.OrgMember;
import com.certguard.entity.Organization;
import com.certguard.entity.User;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.OrgType;
import com.certguard.enums.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 0015 Phase 3 — Testcontainers coverage for {@link BackfillLastActiveOrgService}.
 *
 * <p>Seeds the exact personas from the architect's brief and asserts precisely who moves
 * and who doesn't. Mirrors the Testcontainers conventions of {@code ActiveOrgResolverTest}
 * / {@code WrongOrgAtLoginRegressionTest}: {@code create-drop} schema (Flyway still runs
 * for real via {@code FlywayConfig}'s unconditional bean — see CLAUDE.md — so enum types
 * exist; {@code create-drop} then layers Hibernate's own DDL over the JPA-mapped columns),
 * class-level {@code @Transactional} so each test's seed data rolls back afterward.
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest
@ActiveProfiles("tctest")
@Transactional
class BackfillLastActiveOrgServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_backfill_test")
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

    @Autowired BackfillLastActiveOrgService backfillService;
    @PersistenceContext EntityManager em;

    // Persona 1: genuinely stuck — no valid home membership (revoked), valid membership in
    // org B (newer) and org C (older). Expect apply() to move them to B.
    User stuckUser;
    Organization stuckHomeOrg;
    Organization orgB;
    Organization orgC;

    // Persona 2: normal user with a valid home-org membership. Expect no change.
    User normalUser;
    Organization normalHomeOrg;

    // Persona 3: already-correct — last_active_org_id already points at a valid non-home
    // membership. Expect no change.
    User alreadyCorrectUser;
    Organization alreadyCorrectHomeOrg;
    Organization alreadyCorrectActiveOrg;

    // Persona 4: platform admin with no memberships at all. Expect no change.
    User platformAdmin;
    Organization platformAdminHomeOrg;

    private Organization org(String name) {
        Organization organization = Organization.builder()
                .name(name)
                .slug(name.toLowerCase().replace(" ", "-") + "-" + UUID.randomUUID().toString().substring(0, 8))
                .orgType(OrgType.SINGLE)
                .build();
        em.persist(organization);
        return organization;
    }

    private User user(Organization homeOrg, String email, UserRole role, UUID lastActiveOrgId) {
        User u = User.builder()
                .organization(homeOrg)
                .email(email)
                .name(email)
                .role(role)
                .lastActiveOrgId(lastActiveOrgId)
                .build();
        em.persist(u);
        return u;
    }

    private OrgMember membership(Organization org, User user, InviteStatus status, Instant revokedAt) {
        OrgMember member = OrgMember.builder()
                .organization(org).user(user).role(OrgMemberRole.ENGINEER)
                .inviteStatus(status)
                .revokedAt(revokedAt)
                .build();
        em.persist(member);
        return member;
    }

    /** Forces created_at to an explicit value, bypassing Hibernate's @CreationTimestamp. */
    private void setCreatedAt(OrgMember member, Instant createdAt) {
        em.flush();
        em.createNativeQuery("UPDATE org_members SET created_at = :ts WHERE id = :id")
                .setParameter("ts", createdAt)
                .setParameter("id", member.getId())
                .executeUpdate();
    }

    @BeforeEach
    void seed() {
        // Persona 1: stuck.
        stuckHomeOrg = org("Stuck Home Org");
        orgB = org("Org B");
        orgC = org("Org C");
        stuckUser = user(stuckHomeOrg, "stuck-" + UUID.randomUUID() + "@example.com", UserRole.MEMBER, null);
        // Home membership exists but is revoked — covers the "present but revoked" branch of (A).
        membership(stuckHomeOrg, stuckUser, InviteStatus.ACCEPTED, Instant.now());
        OrgMember membershipC = membership(orgC, stuckUser, InviteStatus.ACCEPTED, null);
        OrgMember membershipB = membership(orgB, stuckUser, InviteStatus.ACCEPTED, null);
        setCreatedAt(membershipC, Instant.now().minus(2, ChronoUnit.DAYS)); // older
        setCreatedAt(membershipB, Instant.now().minus(1, ChronoUnit.HOURS)); // newer

        // Persona 2: normal, valid home membership, last_active_org_id left null.
        normalHomeOrg = org("Normal Home Org");
        normalUser = user(normalHomeOrg, "normal-" + UUID.randomUUID() + "@example.com", UserRole.MEMBER, null);
        membership(normalHomeOrg, normalUser, InviteStatus.ACCEPTED, null);

        // Persona 3: already correct — no valid home membership, but last_active_org_id
        // already points at a valid non-home membership.
        alreadyCorrectHomeOrg = org("Already Correct Home Org");
        alreadyCorrectActiveOrg = org("Already Correct Active Org");
        alreadyCorrectUser = user(alreadyCorrectHomeOrg,
                "already-correct-" + UUID.randomUUID() + "@example.com", UserRole.MEMBER,
                null); // set lastActiveOrgId after the org id is known & membership persisted
        membership(alreadyCorrectActiveOrg, alreadyCorrectUser, InviteStatus.ACCEPTED, null);
        alreadyCorrectUser.setLastActiveOrgId(alreadyCorrectActiveOrg.getId());
        em.merge(alreadyCorrectUser);

        // Persona 4: platform admin, no memberships anywhere.
        platformAdminHomeOrg = org("Platform Admin Home Org");
        platformAdmin = user(platformAdminHomeOrg, "admin-" + UUID.randomUUID() + "@example.com",
                UserRole.PLATFORM_ADMIN, null);

        em.flush();
        em.clear();
    }

    private User reload(UUID id) {
        em.clear();
        return em.find(User.class, id);
    }

    @Test
    void apply_movesGenuinelyStuckUser_toMostRecentValidMembership() {
        List<BackfillLastActiveOrgService.BackfillRow> rows = backfillService.apply();

        assertThat(rows).extracting(BackfillLastActiveOrgService.BackfillRow::userId)
                .contains(stuckUser.getId());
        BackfillLastActiveOrgService.BackfillRow stuckRow = rows.stream()
                .filter(r -> r.userId().equals(stuckUser.getId())).findFirst().orElseThrow();
        assertThat(stuckRow.oldLastActiveOrgId()).isNull();
        assertThat(stuckRow.newOrgId()).isEqualTo(orgB.getId());

        User reloaded = reload(stuckUser.getId());
        assertThat(reloaded.getLastActiveOrgId()).isEqualTo(orgB.getId());
    }

    @Test
    void apply_leavesNormalUserWithValidHomeMembership_untouched() {
        backfillService.apply();

        User reloaded = reload(normalUser.getId());
        assertThat(reloaded.getLastActiveOrgId()).isNull();
    }

    @Test
    void apply_leavesAlreadyCorrectUser_untouched() {
        backfillService.apply();

        User reloaded = reload(alreadyCorrectUser.getId());
        assertThat(reloaded.getLastActiveOrgId()).isEqualTo(alreadyCorrectActiveOrg.getId());
    }

    @Test
    void apply_leavesPlatformAdminWithNoMemberships_untouched() {
        backfillService.apply();

        User reloaded = reload(platformAdmin.getId());
        assertThat(reloaded.getLastActiveOrgId()).isNull();
    }

    @Test
    void apply_isIdempotent_secondRunAffectsZeroRows() {
        List<BackfillLastActiveOrgService.BackfillRow> firstRun = backfillService.apply();
        assertThat(firstRun).extracting(BackfillLastActiveOrgService.BackfillRow::userId)
                .contains(stuckUser.getId());

        List<BackfillLastActiveOrgService.BackfillRow> secondRun = backfillService.apply();
        assertThat(secondRun).isEmpty();

        // No further change beyond the first run's result.
        User reloaded = reload(stuckUser.getId());
        assertThat(reloaded.getLastActiveOrgId()).isEqualTo(orgB.getId());
    }

    @Test
    void dryRun_identifiesAffectedUser_butMakesZeroWrites() {
        List<BackfillLastActiveOrgService.BackfillRow> rows = backfillService.dryRun();

        assertThat(rows).extracting(BackfillLastActiveOrgService.BackfillRow::userId)
                .contains(stuckUser.getId());
        assertThat(rows).extracting(BackfillLastActiveOrgService.BackfillRow::userId)
                .doesNotContain(normalUser.getId(), alreadyCorrectUser.getId(), platformAdmin.getId());

        // Zero writes: re-querying shows the stuck user's column is still null.
        User reloaded = reload(stuckUser.getId());
        assertThat(reloaded.getLastActiveOrgId()).isNull();
    }
}
