package com.certguard.service;

import com.certguard.entity.*;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.service.OrgAuditService;
import com.certguard.service.TokenRevocationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamServiceRevokeMemberTest {

    @Mock OrgMemberRepository memberRepository;
    @Mock InvitationRepository invitationRepository;
    @Mock OrganizationRepository orgRepository;
    @Mock UserRepository userRepository;
    @Mock InvitationService invitationService;
    @Mock EmailDispatchService emailDispatchService;
    @Mock PlatformAdminAuditService platformAdminAuditService;
    @Mock OrgAuditService orgAuditService;
    @Mock TokenRevocationService tokenRevocationService;

    TeamService teamService;

    UUID orgId       = UUID.randomUUID();
    UUID callerId    = UUID.randomUUID();
    UUID targetId    = UUID.randomUUID();
    String callerEmail = "admin@example.com";
    String targetEmail = "member@example.com";

    Organization org;
    User callerUser;
    User targetUser;
    OrgMember callerMember;
    OrgMember targetMember;

    @BeforeEach
    void setUp() {
        teamService = new TeamService(memberRepository, invitationRepository, orgRepository,
                userRepository, invitationService, emailDispatchService,
                platformAdminAuditService, orgAuditService, tokenRevocationService);
        ReflectionTestUtils.setField(teamService, "uiBaseUrl", "http://localhost:8080");

        org = Organization.builder().name("TestOrg").build();
        ReflectionTestUtils.setField(org, "id", orgId);

        callerUser = User.builder().email(callerEmail).name("Admin").build();
        ReflectionTestUtils.setField(callerUser, "id", callerId);

        targetUser = User.builder().email(targetEmail).name("Member").build();
        ReflectionTestUtils.setField(targetUser, "id", targetId);

        callerMember = OrgMember.builder()
                .organization(org).user(callerUser)
                .role(OrgMemberRole.ADMIN).inviteStatus(InviteStatus.ACCEPTED).build();

        targetMember = OrgMember.builder()
                .organization(org).user(targetUser)
                .role(OrgMemberRole.ENGINEER).inviteStatus(InviteStatus.ACCEPTED).build();
    }

    private CertGuardUserPrincipal orgAdminPrincipal() {
        return new CertGuardUserPrincipal(callerId, orgId, callerEmail, false, "ADMIN");
    }

    private CertGuardUserPrincipal platformAdminPrincipal() {
        return new CertGuardUserPrincipal(callerId, orgId, callerEmail, true, null);
    }

    @Nested
    class Guards {

        @Test
        void selfRemoval_throws() {
            CertGuardUserPrincipal principal = orgAdminPrincipal();
            assertThatThrownBy(() -> teamService.revokeMember(orgId, principal, callerId, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot remove yourself");
        }

        @Test
        void memberNotFound_throws() {
            when(memberRepository.findByOrganizationIdAndUserId(orgId, targetId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> teamService.revokeMember(orgId, orgAdminPrincipal(), targetId, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void orgAdminCannotRemoveAnotherAdmin_throws() {
            targetMember = OrgMember.builder()
                    .organization(org).user(targetUser)
                    .role(OrgMemberRole.ADMIN).inviteStatus(InviteStatus.ACCEPTED).build();

            when(memberRepository.findByOrganizationIdAndUserId(orgId, targetId))
                    .thenReturn(Optional.of(targetMember));
            when(memberRepository.findByOrganizationIdAndUserId(orgId, callerId))
                    .thenReturn(Optional.of(callerMember));

            assertThatThrownBy(() -> teamService.revokeMember(orgId, orgAdminPrincipal(), targetId, null))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("cannot remove other admins");
        }

        @Test
        void lastAdminRemoval_throws() {
            targetMember = OrgMember.builder()
                    .organization(org).user(targetUser)
                    .role(OrgMemberRole.ADMIN).inviteStatus(InviteStatus.ACCEPTED).build();

            when(memberRepository.findByOrganizationIdAndUserId(orgId, targetId))
                    .thenReturn(Optional.of(targetMember));
            when(memberRepository.countByOrganizationIdAndRoleAndInviteStatus(
                    orgId, OrgMemberRole.ADMIN, InviteStatus.ACCEPTED)).thenReturn(1L);

            assertThatThrownBy(() -> teamService.revokeMember(orgId, platformAdminPrincipal(), targetId, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("last admin");
        }

        @Test
        void alreadyRevoked_isIdempotent() {
            targetMember = OrgMember.builder()
                    .organization(org).user(targetUser)
                    .role(OrgMemberRole.ENGINEER).inviteStatus(InviteStatus.REVOKED).build();

            when(memberRepository.findByOrganizationIdAndUserId(orgId, targetId))
                    .thenReturn(Optional.of(targetMember));

            assertThatNoException().isThrownBy(
                    () -> teamService.revokeMember(orgId, orgAdminPrincipal(), targetId, null));
            verify(memberRepository, never()).save(any());
        }
    }

    @Nested
    class HappyPath {

        @BeforeEach
        void initTx() {
            TransactionSynchronizationManager.initSynchronization();
        }

        @AfterEach
        void clearTx() {
            TransactionSynchronizationManager.clearSynchronization();
        }

        @BeforeEach
        void stubCommon() {
            when(memberRepository.findByOrganizationIdAndUserId(orgId, targetId))
                    .thenReturn(Optional.of(targetMember));
            when(orgRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(invitationRepository.findAllByOrganizationIdAndEmailIgnoreCaseAndUsedAtIsNull(any(), any()))
                    .thenReturn(List.of());
        }

        @Test
        void orgAdmin_removesEngineer_setsRevoked() {
            when(memberRepository.findByOrganizationIdAndUserId(orgId, callerId))
                    .thenReturn(Optional.of(callerMember));

            teamService.revokeMember(orgId, orgAdminPrincipal(), targetId, null);

            verify(memberRepository).save(argThat(m -> m.getInviteStatus() == InviteStatus.REVOKED));
        }

        @Test
        void platformAdmin_removesEngineer() {
            teamService.revokeMember(orgId, platformAdminPrincipal(), targetId, null);

            verify(memberRepository).save(argThat(m -> m.getInviteStatus() == InviteStatus.REVOKED));
        }

        @Test
        void platformAdmin_canRemoveAdmin_whenNotLast() {
            OrgMember adminTarget = OrgMember.builder()
                    .organization(org).user(targetUser)
                    .role(OrgMemberRole.ADMIN).inviteStatus(InviteStatus.ACCEPTED).build();
            when(memberRepository.findByOrganizationIdAndUserId(orgId, targetId))
                    .thenReturn(Optional.of(adminTarget));
            when(memberRepository.countByOrganizationIdAndRoleAndInviteStatus(
                    orgId, OrgMemberRole.ADMIN, InviteStatus.ACCEPTED)).thenReturn(2L);

            teamService.revokeMember(orgId, platformAdminPrincipal(), targetId, "offboarding");

            verify(memberRepository).save(argThat(m -> m.getInviteStatus() == InviteStatus.REVOKED));
        }

        @Test
        void pendingInvitations_areCancelled() {
            when(memberRepository.findByOrganizationIdAndUserId(orgId, callerId))
                    .thenReturn(Optional.of(callerMember));
            Invitation pending = Invitation.builder()
                    .email(targetEmail).organization(org).tokenHash("hash").build();
            ReflectionTestUtils.setField(pending, "id", UUID.randomUUID());
            when(invitationRepository.findAllByOrganizationIdAndEmailIgnoreCaseAndUsedAtIsNull(orgId, targetEmail))
                    .thenReturn(List.of(pending));

            teamService.revokeMember(orgId, orgAdminPrincipal(), targetId, null);

            verify(invitationRepository).saveAll(argThat(invites ->
                    invites.iterator().next().getUsedAt() != null));
        }
    }
}
