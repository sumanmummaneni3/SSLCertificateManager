package com.certguard.service;

import com.certguard.entity.*;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.SubscriptionStatus;
import com.certguard.repository.*;
import com.certguard.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    @Mock InvitationRepository invitationRepository;
    @Mock OrgMemberRepository memberRepository;
    @Mock UserRepository userRepository;
    @Mock OrganizationRepository orgRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock EmailDispatchService emailDispatchService;

    InvitationService invitationService;

    Organization org;
    Invitation invitation;
    String rawToken = "raw-token-abc";
    String tokenHash;

    @BeforeEach
    void setUp() {
        invitationService = new InvitationService(
                invitationRepository, memberRepository, userRepository,
                orgRepository, subscriptionRepository, jwtTokenProvider,
                emailDispatchService);

        tokenHash = TeamService.sha256(rawToken);

        org = Organization.builder().name("TestOrg").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());

        invitation = Invitation.builder()
                .tokenHash(tokenHash)
                .email("invited@example.com")
                .organization(org)
                .role(OrgMemberRole.ENGINEER)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
        ReflectionTestUtils.setField(invitation, "id", UUID.randomUUID());
    }

    @Nested
    class ValidateInviteAndSendOtp {

        @Test
        void happyPath_returnsEmail() {
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));

            String result = invitationService.validateInviteAndSendOtp(rawToken);

            assertThat(result).isEqualTo("invited@example.com");
        }

        @Test
        void invalidToken_throws() {
            when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> invitationService.validateInviteAndSendOtp("bad-token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid or expired");
        }

        @Test
        void alreadyUsed_throws() {
            invitation.setUsedAt(Instant.now());
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));

            assertThatThrownBy(() -> invitationService.validateInviteAndSendOtp(rawToken))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already been used");
        }

        @Test
        void expiredInvitation_throws() {
            invitation.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));

            assertThatThrownBy(() -> invitationService.validateInviteAndSendOtp(rawToken))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        void otpEmailDelegatedToEmailDispatchService() {
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));

            invitationService.validateInviteAndSendOtp(rawToken);

            verify(emailDispatchService).sendOtpEmail(eq("invited@example.com"), anyString(), eq("TestOrg"));
        }
    }

    @Nested
    class AcceptInvite {

        @BeforeEach
        void seedOtp() {
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
            invitationService.validateInviteAndSendOtp(rawToken);
        }

        @Test
        void happyPath_returnsToken() {
            // extract the OTP that was stored
            Map<String, Object> otpStore = (Map<String, Object>) ReflectionTestUtils.getField(invitationService, "otpStore");
            Object entry = otpStore.get(tokenHash);
            String otp = (String) ReflectionTestUtils.invokeMethod(entry, "otp");

            User user = User.builder().email("invited@example.com").build();
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            OrgMember member = OrgMember.builder().organization(org).user(user).role(OrgMemberRole.ENGINEER).build();

            when(userRepository.findByEmail("invited@example.com")).thenReturn(Optional.of(user));
            when(memberRepository.findByOrganizationIdAndUserId(any(), any())).thenReturn(Optional.of(member));
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
            when(jwtTokenProvider.generateToken(any(), any(), any(), any())).thenReturn("jwt-token");

            Map<String, String> result = invitationService.acceptInvite(rawToken, "invited@example.com", otp);

            assertThat(result).containsKey("token");
            assertThat(result.get("token")).isEqualTo("jwt-token");
        }

        @Test
        void wrongEmail_throws() {
            Map<String, Object> otpStore = (Map<String, Object>) ReflectionTestUtils.getField(invitationService, "otpStore");
            Object entry = otpStore.get(tokenHash);
            String otp = (String) ReflectionTestUtils.invokeMethod(entry, "otp");

            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));

            assertThatThrownBy(() -> invitationService.acceptInvite(rawToken, "other@example.com", otp))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email does not match");
        }

        @Test
        void wrongOtp_throws() {
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));

            assertThatThrownBy(() -> invitationService.acceptInvite(rawToken, "invited@example.com", "999999"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Incorrect OTP");
        }

        @Test
        void missingOtpEntry_throwsExpired() {
            // Accept without a prior validate call so no OTP entry exists
            InvitationService freshService = new InvitationService(
                    invitationRepository, memberRepository, userRepository,
                    orgRepository, subscriptionRepository, jwtTokenProvider,
                    emailDispatchService);

            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));

            assertThatThrownBy(() -> freshService.acceptInvite(rawToken, "invited@example.com", "123456"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OTP has expired");
        }

        @Test
        void reuseBlocked_otpRemovedAfterAccept() {
            Map<Object, Object> otpStore = (Map<Object, Object>) ReflectionTestUtils.getField(invitationService, "otpStore");
            Object entry = otpStore.get(tokenHash);
            String otp = (String) ReflectionTestUtils.invokeMethod(entry, "otp");

            User user = User.builder().email("invited@example.com").build();
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            OrgMember member = OrgMember.builder().organization(org).user(user).role(OrgMemberRole.ENGINEER).build();

            when(userRepository.findByEmail("invited@example.com")).thenReturn(Optional.of(user));
            when(memberRepository.findByOrganizationIdAndUserId(any(), any())).thenReturn(Optional.of(member));
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
            when(jwtTokenProvider.generateToken(any(), any(), any(), any())).thenReturn("jwt");

            invitationService.acceptInvite(rawToken, "invited@example.com", otp);

            // Second attempt — OTP removed, invitation.usedAt is set
            assertThatThrownBy(() -> invitationService.acceptInvite(rawToken, "invited@example.com", otp))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
