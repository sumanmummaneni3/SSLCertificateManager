package com.certguard.auth.service;

import com.certguard.auth.exception.AuthException;
import com.certguard.auth.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * RFC 0015 Phase 1: unit coverage for {@link AuthProvisioningService#resolveOrProvision}'s
 * org-resolution order — the RS256-path mirror of {@code ActiveOrgResolverTest} in the
 * server module. Uses a mocked {@link JdbcTemplate} (this service talks to the main
 * certguard DB via raw JDBC, not JPA, and the auth-service module has no Testcontainers
 * wiring) with a single routing Answer that inspects the SQL text and bound parameters,
 * so we can assert both the returned {@link OrgContextRecord} and the exact SQL used for
 * the fallback query — the pre-fix bug was specifically an ORDER BY ASC (should be DESC)
 * with a missing revoked_at filter.
 */
@ExtendWith(MockitoExtension.class)
class AuthProvisioningServiceTest {

    @Mock JdbcTemplate mainJdbc;

    AuthProvisioningService service;

    UUID userId;
    UUID homeOrgId;
    final String email = "invited-user@example.com";

    List<String> capturedSql;
    /** users table row(s) returned for "SELECT ... FROM users WHERE email = ?". */
    List<Map<String, Object>> usersRow;
    /** org_id -> role, for a valid (ACCEPTED, non-revoked) membership at that org. Orgs absent here resolve to "no membership". */
    Map<UUID, String> validMembershipRoleByOrgId;
    /** Result for the "most-recent membership across all orgs" fallback query. */
    List<Map<String, Object>> mostRecentMembershipResult;
    /** Whether a live (non-expired) revoked_tokens row exists for the (userId, requested org) pair. */
    boolean revokedSessionExists;

    @BeforeEach
    void setUp() {
        service = new AuthProvisioningService(mainJdbc, "");
        userId = UUID.randomUUID();
        homeOrgId = UUID.randomUUID();
        capturedSql = new ArrayList<>();
        usersRow = List.of();
        validMembershipRoleByOrgId = new HashMap<>();
        mostRecentMembershipResult = List.of();
        revokedSessionExists = false;

        lenient().doAnswer(inv -> {
            Object[] args = inv.getArguments();
            String sql = (String) args[0];
            capturedSql.add(sql);

            if (sql.contains("FROM revoked_tokens")) {
                return revokedSessionExists
                        ? List.<Map<String, Object>>of(Map.of("?column?", 1))
                        : List.<Map<String, Object>>of();
            }
            if (sql.contains("FROM users WHERE email")) {
                return usersRow;
            }
            if (sql.contains("FROM org_members") && sql.contains("org_id = ?") && sql.contains("user_id = ?")) {
                UUID orgIdArg = (UUID) args[1];
                String role = validMembershipRoleByOrgId.get(orgIdArg);
                return role == null ? List.<Map<String, Object>>of() : List.<Map<String, Object>>of(Map.of("role", role));
            }
            if (sql.contains("ORDER BY created_at")) {
                return mostRecentMembershipResult;
            }
            return List.<Map<String, Object>>of();
        }).when(mainJdbc).queryForList(anyString(), any(Object[].class));

        lenient().doAnswer(inv -> {
            capturedSql.add((String) inv.getArguments()[0]);
            return 1;
        }).when(mainJdbc).update(anyString(), any(Object[].class));
    }

    private Map<String, Object> userRow(UUID orgId, String role, UUID lastActiveOrgId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", userId);
        row.put("org_id", orgId);
        row.put("role", role);
        row.put("last_active_org_id", lastActiveOrgId);
        return row;
    }

    @Test
    void lastActiveOrgValid_resolvesToLastActiveOrg_noWriteBack() {
        UUID lastActiveOrgId = UUID.randomUUID();
        usersRow = List.of(userRow(homeOrgId, "MEMBER", lastActiveOrgId));
        validMembershipRoleByOrgId.put(lastActiveOrgId, "ADMIN");
        validMembershipRoleByOrgId.put(homeOrgId, "VIEWER"); // must NOT be chosen — last-active wins

        OrgContextRecord ctx = service.resolveOrProvision(email, "Invited User");

        assertThat(ctx.orgId()).isEqualTo(lastActiveOrgId);
        assertThat(ctx.orgRole()).isEqualTo("ADMIN");
        assertThat(ctx.platformAdmin()).isFalse();
        // Already equal to last_active_org_id in the DB — no write-back UPDATE
        verify(mainJdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void lastActiveOrgInvalid_fallsBackToHomeOrg_andWritesBack() {
        UUID lastActiveOrgId = UUID.randomUUID(); // stale/revoked — no entry in validMembershipRoleByOrgId
        usersRow = List.of(userRow(homeOrgId, "MEMBER", lastActiveOrgId));
        validMembershipRoleByOrgId.put(homeOrgId, "VIEWER");

        OrgContextRecord ctx = service.resolveOrProvision(email, "Invited User");

        assertThat(ctx.orgId()).isEqualTo(homeOrgId);
        assertThat(ctx.orgRole()).isEqualTo("VIEWER");
        assertThat(capturedSql.stream().anyMatch(sql -> sql.contains("last_active_org_id"))).isTrue();
    }

    @Test
    void homeOrgInvalid_fallsBackToMostRecentMembership_orderedDescWithRevokedFilter() {
        UUID mostRecentOrgId = UUID.randomUUID();
        usersRow = List.of(userRow(homeOrgId, "MEMBER", null)); // no last_active_org_id at all
        // homeOrgId absent from validMembershipRoleByOrgId — home lookup returns invalid
        mostRecentMembershipResult = List.of(Map.of("org_id", mostRecentOrgId, "role", "ENGINEER"));

        OrgContextRecord ctx = service.resolveOrProvision(email, "Invited User");

        assertThat(ctx.orgId()).isEqualTo(mostRecentOrgId);
        assertThat(ctx.orgRole()).isEqualTo("ENGINEER");

        String fallbackSql = capturedSql.stream()
                .filter(sql -> sql.contains("ORDER BY created_at"))
                .findFirst().orElseThrow();
        assertThat(fallbackSql).contains("ORDER BY created_at DESC");
        assertThat(fallbackSql).contains("revoked_at IS NULL");
        assertThat(capturedSql.stream().anyMatch(sql -> sql.contains("last_active_org_id"))).isTrue();
    }

    @Test
    void noMembershipAnywhere_resolvesToHomeOrg_withLeastPrivilegeRole_neverAdmin() {
        usersRow = List.of(userRow(homeOrgId, "MEMBER", null));
        // validMembershipRoleByOrgId and mostRecentMembershipResult both empty — no membership anywhere

        OrgContextRecord ctx = service.resolveOrProvision(email, "Invited User");

        assertThat(ctx.orgId()).isEqualTo(homeOrgId);
        assertThat(ctx.orgRole()).isEqualTo("VIEWER");
        assertThat(ctx.orgRole()).isNotEqualTo("ADMIN");
    }

    @Test
    void platformAdmin_skipsResolution_orgRoleNull_homeOrg() {
        usersRow = List.of(userRow(homeOrgId, "PLATFORM_ADMIN", null));

        OrgContextRecord ctx = service.resolveOrProvision(email, "Admin User");

        assertThat(ctx.platformAdmin()).isTrue();
        assertThat(ctx.orgId()).isEqualTo(homeOrgId);
        assertThat(ctx.orgRole()).isNull();
        verify(mainJdbc, never()).update(anyString(), any(Object[].class));
    }

    // -------------------------------------------------------------------------
    // RFC 0015 Phase 2 — switchActiveOrg (explicit, user-driven switch)
    // -------------------------------------------------------------------------

    @Test
    void switchActiveOrg_validMembership_persistsLastActiveOrgId_andReturnsCtx() {
        UUID requestedOrgId = UUID.randomUUID();
        usersRow = List.of(userRow(homeOrgId, "MEMBER", null));
        validMembershipRoleByOrgId.put(requestedOrgId, "ENGINEER");

        OrgContextRecord ctx = service.switchActiveOrg(email, requestedOrgId);

        assertThat(ctx.orgId()).isEqualTo(requestedOrgId);
        assertThat(ctx.orgRole()).isEqualTo("ENGINEER");
        assertThat(ctx.platformAdmin()).isFalse();
        assertThat(capturedSql.stream().anyMatch(sql ->
                sql.contains("UPDATE users SET last_active_org_id"))).isTrue();
    }

    @Test
    void switchActiveOrg_nonMemberOrg_throwsForbidden_noPersistence() {
        UUID requestedOrgId = UUID.randomUUID();
        usersRow = List.of(userRow(homeOrgId, "MEMBER", null));
        // requestedOrgId absent from validMembershipRoleByOrgId — no membership

        assertThatThrownBy(() -> service.switchActiveOrg(email, requestedOrgId))
                .isInstanceOf(ForbiddenException.class);

        assertThat(capturedSql.stream().anyMatch(sql ->
                sql.contains("UPDATE users SET last_active_org_id"))).isFalse();
    }

    @Test
    void switchActiveOrg_revokedMembership_throwsForbidden() {
        UUID requestedOrgId = UUID.randomUUID();
        usersRow = List.of(userRow(homeOrgId, "MEMBER", null));
        // A revoked membership is filtered out at the "validMembershipRole" query level —
        // same as "no membership at all" from this service's point of view.

        assertThatThrownBy(() -> service.switchActiveOrg(email, requestedOrgId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void switchActiveOrg_revokedSession_throwsForbidden_noPersistence() {
        UUID requestedOrgId = UUID.randomUUID();
        usersRow = List.of(userRow(homeOrgId, "MEMBER", null));
        validMembershipRoleByOrgId.put(requestedOrgId, "ADMIN");
        revokedSessionExists = true;

        assertThatThrownBy(() -> service.switchActiveOrg(email, requestedOrgId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("revoked");

        assertThat(capturedSql.stream().anyMatch(sql ->
                sql.contains("UPDATE users SET last_active_org_id"))).isFalse();
    }

    @Test
    void switchActiveOrg_platformAdminCaller_rejected() {
        UUID requestedOrgId = UUID.randomUUID();
        usersRow = List.of(userRow(homeOrgId, "PLATFORM_ADMIN", null));
        validMembershipRoleByOrgId.put(requestedOrgId, "ADMIN"); // even if a row existed, must still reject

        assertThatThrownBy(() -> service.switchActiveOrg(email, requestedOrgId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("act-as-org");

        verify(mainJdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void switchActiveOrg_userNotFound_throwsAuthException() {
        usersRow = List.of();

        assertThatThrownBy(() -> service.switchActiveOrg(email, UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
    }
}
