package com.certguard.controller;

import com.certguard.entity.OrgMember;
import com.certguard.entity.Organization;
import com.certguard.entity.User;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.repository.OrgMemberRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.UserRepository;
import com.certguard.security.CertGuardUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RFC 0015 Phase 2 (frontend org-switcher follow-up): MeController's {@code memberships}
 * list must only include orgs the user could actually switch to — ACCEPTED, non-revoked —
 * so the UI switcher never offers a dead entry and a 403-recovery {@code /me} refetch
 * actually drops it.
 */
@ExtendWith(MockitoExtension.class)
class MeControllerTest {

    @Mock OrgMemberRepository orgMemberRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock UserRepository userRepository;

    MeController controller;

    UUID userId;
    CertGuardUserPrincipal principal;

    @BeforeEach
    void setUp() {
        controller = new MeController(orgMemberRepository, organizationRepository, userRepository);
        userId = UUID.randomUUID();
        principal = new CertGuardUserPrincipal(userId, UUID.randomUUID(), "user@example.com", false, "ADMIN");
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.empty());
    }

    private OrgMember memberOf(String orgName, OrgMemberRole role) {
        Organization org = Organization.builder().name(orgName).build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        User user = User.builder().email("user@example.com").build();
        ReflectionTestUtils.setField(user, "id", userId);
        OrgMember member = OrgMember.builder()
                .organization(org).user(user).role(role)
                .inviteStatus(InviteStatus.ACCEPTED)
                .build();
        ReflectionTestUtils.setField(member, "id", UUID.randomUUID());
        return member;
    }

    @Test
    @SuppressWarnings("unchecked")
    void memberships_onlyIncludesAcceptedNonRevoked_queriesFilteredRepoMethod() {
        OrgMember acceptedMember = memberOf("Active Org", OrgMemberRole.ADMIN);
        when(orgMemberRepository.findAllByUserIdAndInviteStatusAndRevokedAtIsNull(
                eq(userId), eq(InviteStatus.ACCEPTED)))
                .thenReturn(List.of(acceptedMember));

        ResponseEntity<Map<String, Object>> response = controller.me(principal);

        List<Map<String, Object>> memberships =
                (List<Map<String, Object>>) response.getBody().get("memberships");

        assertThat(memberships).hasSize(1);
        assertThat(memberships.get(0).get("orgName")).isEqualTo("Active Org");

        // The unfiltered findAllByUserId must never be used for this list — that was the bug:
        // revoked/pending memberships leaking into the switcher dropdown.
        org.mockito.Mockito.verify(orgMemberRepository, org.mockito.Mockito.never()).findAllByUserId(userId);
    }
}
