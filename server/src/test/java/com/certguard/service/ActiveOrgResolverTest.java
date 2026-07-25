package com.certguard.service;

import com.certguard.entity.OrgMember;
import com.certguard.entity.Organization;
import com.certguard.entity.User;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.repository.OrgMemberRepository;
import com.certguard.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RFC 0015 Phase 1: unit coverage for {@link ActiveOrgResolver}'s org-resolution
 * order, mirrored by {@code AuthProvisioningServiceResolutionTest} in the
 * auth-service for the RS256 path.
 */
@ExtendWith(MockitoExtension.class)
class ActiveOrgResolverTest {

    @Mock OrgMemberRepository orgMemberRepository;
    @Mock UserRepository userRepository;

    ActiveOrgResolver resolver;

    UUID homeOrgId;
    User user;

    @BeforeEach
    void setUp() {
        resolver = new ActiveOrgResolver(orgMemberRepository, userRepository);

        homeOrgId = UUID.randomUUID();
        Organization homeOrg = Organization.builder().name("Home Org").build();
        ReflectionTestUtils.setField(homeOrg, "id", homeOrgId);

        user = User.builder().organization(homeOrg).email("user@example.com").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    }

    private OrgMember memberOf(UUID orgId, OrgMemberRole role) {
        Organization org = Organization.builder().name("Org").build();
        ReflectionTestUtils.setField(org, "id", orgId);
        OrgMember member = OrgMember.builder()
                .organization(org).user(user).role(role)
                .inviteStatus(InviteStatus.ACCEPTED)
                .build();
        ReflectionTestUtils.setField(member, "id", UUID.randomUUID());
        return member;
    }

    @Test
    void homeOrgValid_resolvesToHomeOrg_andRoleFromMembership() {
        // lastActiveOrgId is null, so step 1 is skipped entirely
        when(orgMemberRepository.findByOrganizationIdAndUserIdAndInviteStatusAndRevokedAtIsNull(
                eq(homeOrgId), eq(user.getId()), eq(InviteStatus.ACCEPTED)))
                .thenReturn(Optional.of(memberOf(homeOrgId, OrgMemberRole.ENGINEER)));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        ActiveOrgResolver.ActiveOrgContext ctx = resolver.resolve(user);

        assertThat(ctx.orgId()).isEqualTo(homeOrgId);
        assertThat(ctx.orgRole()).isEqualTo("ENGINEER");
    }

    @Test
    void lastActiveOrgValid_resolvesToLastActiveOrg_stickyOverridesHome() {
        UUID lastActiveOrgId = UUID.randomUUID();
        user.setLastActiveOrgId(lastActiveOrgId);

        when(orgMemberRepository.findByOrganizationIdAndUserIdAndInviteStatusAndRevokedAtIsNull(
                eq(lastActiveOrgId), eq(user.getId()), eq(InviteStatus.ACCEPTED)))
                .thenReturn(Optional.of(memberOf(lastActiveOrgId, OrgMemberRole.ADMIN)));

        ActiveOrgResolver.ActiveOrgContext ctx = resolver.resolve(user);

        assertThat(ctx.orgId()).isEqualTo(lastActiveOrgId);
        assertThat(ctx.orgRole()).isEqualTo("ADMIN");
        // Already equal to user.lastActiveOrgId — no write-back needed
        verify(userRepository, never()).save(any());
    }

    @Test
    void lastActiveOrgInvalid_fallsBackToHomeOrg_whenHomeValid() {
        UUID lastActiveOrgId = UUID.randomUUID(); // revoked/stale — no valid membership
        user.setLastActiveOrgId(lastActiveOrgId);

        when(orgMemberRepository.findByOrganizationIdAndUserIdAndInviteStatusAndRevokedAtIsNull(
                eq(lastActiveOrgId), eq(user.getId()), eq(InviteStatus.ACCEPTED)))
                .thenReturn(Optional.empty());
        when(orgMemberRepository.findByOrganizationIdAndUserIdAndInviteStatusAndRevokedAtIsNull(
                eq(homeOrgId), eq(user.getId()), eq(InviteStatus.ACCEPTED)))
                .thenReturn(Optional.of(memberOf(homeOrgId, OrgMemberRole.VIEWER)));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        ActiveOrgResolver.ActiveOrgContext ctx = resolver.resolve(user);

        assertThat(ctx.orgId()).isEqualTo(homeOrgId);
        assertThat(ctx.orgRole()).isEqualTo("VIEWER");
        // orgId changed from stale lastActiveOrgId to homeOrgId — sticky write-back fires
        verify(userRepository).save(user);
        assertThat(user.getLastActiveOrgId()).isEqualTo(homeOrgId);
    }

    @Test
    void homeOrgInvalid_fallsBackToMostRecentMembership_orderedByCreatedAtDesc() {
        UUID mostRecentOrgId = UUID.randomUUID();

        when(orgMemberRepository.findByOrganizationIdAndUserIdAndInviteStatusAndRevokedAtIsNull(
                eq(homeOrgId), eq(user.getId()), eq(InviteStatus.ACCEPTED)))
                .thenReturn(Optional.empty());
        when(orgMemberRepository.findFirstByUserIdAndInviteStatusAndRevokedAtIsNullOrderByCreatedAtDesc(
                eq(user.getId()), eq(InviteStatus.ACCEPTED)))
                .thenReturn(Optional.of(memberOf(mostRecentOrgId, OrgMemberRole.ENGINEER)));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        ActiveOrgResolver.ActiveOrgContext ctx = resolver.resolve(user);

        assertThat(ctx.orgId()).isEqualTo(mostRecentOrgId);
        assertThat(ctx.orgRole()).isEqualTo("ENGINEER");
        verify(userRepository).save(user);
        assertThat(user.getLastActiveOrgId()).isEqualTo(mostRecentOrgId);
    }

    @Test
    void noMembershipAnywhere_resolvesToHomeOrg_withLeastPrivilegeRole_neverAdmin() {
        when(orgMemberRepository.findByOrganizationIdAndUserIdAndInviteStatusAndRevokedAtIsNull(
                eq(homeOrgId), eq(user.getId()), eq(InviteStatus.ACCEPTED)))
                .thenReturn(Optional.empty());
        when(orgMemberRepository.findFirstByUserIdAndInviteStatusAndRevokedAtIsNullOrderByCreatedAtDesc(
                eq(user.getId()), eq(InviteStatus.ACCEPTED)))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        ActiveOrgResolver.ActiveOrgContext ctx = resolver.resolve(user);

        assertThat(ctx.orgId()).isEqualTo(homeOrgId);
        assertThat(ctx.orgRole()).isEqualTo("VIEWER");
        assertThat(ctx.orgRole()).isNotEqualTo("ADMIN");
    }

    @Test
    void writeBack_persistsResolvedOrgId_onlyWhenItDiffersFromCurrentLastActiveOrgId() {
        // lastActiveOrgId already equals the org that will resolve (home) — no save() call
        user.setLastActiveOrgId(homeOrgId);
        when(orgMemberRepository.findByOrganizationIdAndUserIdAndInviteStatusAndRevokedAtIsNull(
                eq(homeOrgId), eq(user.getId()), eq(InviteStatus.ACCEPTED)))
                .thenReturn(Optional.of(memberOf(homeOrgId, OrgMemberRole.ADMIN)));

        resolver.resolve(user);

        verify(userRepository, never()).save(any());
    }
}
