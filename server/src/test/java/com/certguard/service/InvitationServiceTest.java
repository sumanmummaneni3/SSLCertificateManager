package com.certguard.service;

import com.certguard.entity.Invitation;
import com.certguard.entity.InvitationOtp;
import com.certguard.entity.OrgMember;
import com.certguard.entity.Organization;
import com.certguard.entity.User;
import com.certguard.enums.OrgMemberRole;
import com.certguard.repository.*;
import com.certguard.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    @Mock InvitationRepository invitationRepository;
    @Mock OrgMemberRepository memberRepository;
    @Mock UserRepository userRepository;
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock EmailDispatchService emailDispatchService;
    @Mock TokenRevocationService tokenRevocationService;
    @Mock InvitationOtpRepository otpRepository;

    BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    InvitationService invitationService;

    Organization org;
    Invitation invitation;
    String rawToken = "raw-token-abc";
    String tokenHash;
    UUID orgId;

    @BeforeEach
    void setUp() {
        invitationService = new InvitationService(
                invitationRepository, memberRepository, userRepository,
                jwtTokenProvider, emailDispatchService, tokenRevocationService,
                otpRepository, passwordEncoder);

        tokenHash = TeamService.sha256(rawToken);
        orgId = UUID.randomUUID();

        org = Organization.builder().name("TestOrg").build();
        ReflectionTestUtils.setField(org, "id", orgId);

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
            when(otpRepository.findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(
                    eq("invited@example.com"), eq(orgId), any())).thenReturn(Optional.empty());
            when(otpRepository.save(any(InvitationOtp.class))).thenAnswer(i -> i.getArgument(0));

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
            when(otpRepository.findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(
                    any(), any(), any())).thenReturn(Optional.empty());
            when(otpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            invitationService.validateInviteAndSendOtp(rawToken);

            verify(emailDispatchService).sendOtpEmail(eq("invited@example.com"), anyString(), eq("TestOrg"));
        }

        @Test
        void otpHashedWithBCryptBeforeStorage() {
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
            when(otpRepository.findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(
                    any(), any(), any())).thenReturn(Optional.empty());
            when(otpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            invitationService.validateInviteAndSendOtp(rawToken);

            ArgumentCaptor<InvitationOtp> captor = ArgumentCaptor.forClass(InvitationOtp.class);
            verify(otpRepository).save(captor.capture());
            InvitationOtp saved = captor.getValue();
            // The hash must not equal the plaintext 6-digit OTP
            assertThat(saved.getOtpHash()).startsWith("$2a$");
            assertThat(saved.getEmail()).isEqualTo("invited@example.com");
            assertThat(saved.getOrgId()).isEqualTo(orgId);
        }

        @Test
        void cooldownEnforced_whenUnexpiredOtpExists() {
            InvitationOtp existing = InvitationOtp.builder()
                    .email("invited@example.com")
                    .orgId(orgId)
                    .otpHash("$2a$10$dummy")
                    .expiresAt(Instant.now().plus(9, ChronoUnit.MINUTES))
                    .sentAt(Instant.now().minusSeconds(10)) // only 10s ago — under 60s cooldown
                    .resendCount(0)
                    .attempts(0)
                    .build();

            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
            when(otpRepository.findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(
                    any(), any(), any())).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> invitationService.validateInviteAndSendOtp(rawToken))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Please wait");
        }

        @Test
        void resendCapEnforced_whenMaxResendsReached() {
            InvitationOtp existing = InvitationOtp.builder()
                    .email("invited@example.com")
                    .orgId(orgId)
                    .otpHash("$2a$10$dummy")
                    .expiresAt(Instant.now().plus(9, ChronoUnit.MINUTES))
                    .sentAt(Instant.now().minusSeconds(120)) // past cooldown
                    .resendCount(3) // MAX_OTP_RESENDS reached
                    .attempts(0)
                    .build();

            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
            when(otpRepository.findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(
                    any(), any(), any())).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> invitationService.validateInviteAndSendOtp(rawToken))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Too many codes requested");
        }
    }

    @Nested
    class AcceptInvite {

        private String plainOtp;
        private InvitationOtp otpEntry;

        @BeforeEach
        void seedOtp() {
            plainOtp = "123456";
            otpEntry = InvitationOtp.builder()
                    .email("invited@example.com")
                    .orgId(orgId)
                    .otpHash(passwordEncoder.encode(plainOtp))
                    .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
                    .sentAt(Instant.now())
                    .resendCount(0)
                    .attempts(0)
                    .build();
            ReflectionTestUtils.setField(otpEntry, "id", UUID.randomUUID());
        }

        @Test
        void happyPath_returnsToken() {
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
            when(otpRepository.findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(
                    eq("invited@example.com"), eq(orgId), any())).thenReturn(Optional.of(otpEntry));

            User user = User.builder().email("invited@example.com").build();
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            OrgMember member = OrgMember.builder().organization(org).user(user).role(OrgMemberRole.ENGINEER).build();

            when(userRepository.findByEmail("invited@example.com")).thenReturn(Optional.of(user));
            when(memberRepository.findByOrganizationIdAndUserId(any(), any())).thenReturn(Optional.of(member));
            when(jwtTokenProvider.generateToken(any(), any(), any(), any())).thenReturn("jwt-token");

            Map<String, String> result = invitationService.acceptInvite(rawToken, "invited@example.com", plainOtp);

            assertThat(result).containsKey("token");
            assertThat(result.get("token")).isEqualTo("jwt-token");
            // OTP row must be deleted after successful acceptance
            verify(otpRepository).deleteByEmailAndOrgId("invited@example.com", orgId);
        }

        @Test
        void wrongEmail_throws() {
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));

            assertThatThrownBy(() -> invitationService.acceptInvite(rawToken, "other@example.com", plainOtp))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email does not match");
        }

        @Test
        void wrongOtp_throws() {
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
            when(otpRepository.findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(
                    any(), any(), any())).thenReturn(Optional.of(otpEntry));
            when(otpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> invitationService.acceptInvite(rawToken, "invited@example.com", "999999"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Incorrect OTP");
        }

        @Test
        void missingOtpEntry_throwsExpired() {
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
            when(otpRepository.findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(
                    any(), any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> invitationService.acceptInvite(rawToken, "invited@example.com", "123456"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OTP has expired");
        }

        @Test
        void tooManyWrongAttempts_deletesOtpAndThrows() {
            // Set attempts to MAX_OTP_ATTEMPTS - 1 so next wrong attempt triggers lockout
            InvitationOtp nearLockout = InvitationOtp.builder()
                    .email("invited@example.com")
                    .orgId(orgId)
                    .otpHash(passwordEncoder.encode("654321"))
                    .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
                    .sentAt(Instant.now())
                    .resendCount(0)
                    .attempts(4) // one away from MAX_OTP_ATTEMPTS=5
                    .build();
            ReflectionTestUtils.setField(nearLockout, "id", UUID.randomUUID());

            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
            when(otpRepository.findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(
                    any(), any(), any())).thenReturn(Optional.of(nearLockout));

            assertThatThrownBy(() -> invitationService.acceptInvite(rawToken, "invited@example.com", "000000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Too many incorrect attempts");

            verify(otpRepository).deleteByEmailAndOrgId("invited@example.com", orgId);
        }
    }
}
