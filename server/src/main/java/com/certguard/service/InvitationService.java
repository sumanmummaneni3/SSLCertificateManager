package com.certguard.service;

import com.certguard.dto.response.InvitationResponse;
import com.certguard.entity.*;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.UserRole;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
import com.certguard.security.JwtTokenProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final InvitationRepository invitationRepository;
    private final OrgMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final EmailDispatchService emailDispatchService;
    private final TokenRevocationService tokenRevocationService;
    private final InvitationOtpRepository otpRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    private static final int MAX_OTP_ATTEMPTS  = 5;
    private static final int MAX_OTP_RESENDS   = 3;
    private static final long OTP_COOLDOWN_SECONDS = 60;

    public InvitationService(InvitationRepository invitationRepository,
                             OrgMemberRepository memberRepository,
                             UserRepository userRepository,
                             JwtTokenProvider jwtTokenProvider,
                             EmailDispatchService emailDispatchService,
                             TokenRevocationService tokenRevocationService,
                             InvitationOtpRepository otpRepository,
                             BCryptPasswordEncoder passwordEncoder) {
        this.invitationRepository   = invitationRepository;
        this.memberRepository       = memberRepository;
        this.userRepository         = userRepository;
        this.jwtTokenProvider       = jwtTokenProvider;
        this.emailDispatchService   = emailDispatchService;
        this.tokenRevocationService = tokenRevocationService;
        this.otpRepository          = otpRepository;
        this.passwordEncoder        = passwordEncoder;
    }

    // -- Step 1: validate invite token and send OTP ----------------------------

    /**
     * Called when the invited user lands on /invite?token=<raw>.
     * Validates the invite, sends a 6-digit OTP to their email, returns the
     * email address so the frontend can pre-fill it.
     */
    @Transactional
    public String validateInviteAndSendOtp(String rawToken) {
        String hash = TeamService.sha256(rawToken);
        Invitation inv = invitationRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired invitation link"));

        if (inv.getUsedAt() != null) {
            throw new IllegalArgumentException("This invitation has already been used");
        }
        if (inv.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("This invitation link has expired");
        }

        String email  = inv.getEmail();
        UUID   orgId  = inv.getOrganization().getId();

        InvitationOtp existing = otpRepository
                .findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(email, orgId, Instant.now())
                .orElse(null);

        if (existing != null) {
            // Unexpired OTP exists — enforce cool-down and resend cap
            long secondsSinceSent = ChronoUnit.SECONDS.between(existing.getSentAt(), Instant.now());
            if (secondsSinceSent < OTP_COOLDOWN_SECONDS) {
                throw new IllegalArgumentException(
                        "Please wait " + (OTP_COOLDOWN_SECONDS - secondsSinceSent) + " seconds before requesting another code");
            }
            if (existing.getResendCount() >= MAX_OTP_RESENDS) {
                throw new IllegalArgumentException(
                        "Too many codes requested — please ask the admin to send a new invitation");
            }
            // Resend: delete old entry, issue a fresh one
            otpRepository.deleteByEmailAndOrgId(email, orgId);
            String otp = generateOtp();
            saveOtp(email, orgId, otp, existing.getResendCount() + 1);
            emailDispatchService.sendOtpEmail(email, otp, inv.getOrganization().getName());
            log.info("OTP resent ({}/{}) for invite {} to {}",
                    existing.getResendCount() + 1, MAX_OTP_RESENDS, inv.getId(), email);
        } else {
            // No unexpired OTP — generate a fresh one
            otpRepository.deleteByEmailAndOrgId(email, orgId); // remove any expired leftovers
            String otp = generateOtp();
            saveOtp(email, orgId, otp, 0);
            emailDispatchService.sendOtpEmail(email, otp, inv.getOrganization().getName());
            log.info("OTP sent for invite {} to {}", inv.getId(), email);
        }
        return email;
    }

    // -- Step 2: verify OTP, create user + membership, issue JWT ---------------

    @Transactional
    public Map<String, String> acceptInvite(String rawToken, String submittedEmail, String submittedOtp) {
        String hash = TeamService.sha256(rawToken);

        Invitation inv = invitationRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid invitation"));

        if (inv.getUsedAt() != null) throw new IllegalArgumentException("Already used");
        if (inv.getExpiresAt().isBefore(Instant.now())) throw new IllegalArgumentException("Invitation expired");
        if (!inv.getEmail().equalsIgnoreCase(submittedEmail.trim()))
            throw new IllegalArgumentException("Email does not match invitation");

        UUID   orgId = inv.getOrganization().getId();
        String email = inv.getEmail();

        InvitationOtp entry = otpRepository
                .findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(email, orgId, Instant.now())
                .orElse(null);

        if (entry == null) {
            throw new IllegalArgumentException("OTP has expired — please request a new one");
        }

        if (!passwordEncoder.matches(submittedOtp.trim(), entry.getOtpHash())) {
            int attempts = entry.getAttempts() + 1;
            if (attempts >= MAX_OTP_ATTEMPTS) {
                otpRepository.deleteByEmailAndOrgId(email, orgId);
                throw new IllegalArgumentException("Too many incorrect attempts — please request a new OTP");
            }
            entry.setAttempts(attempts);
            otpRepository.save(entry);
            throw new IllegalArgumentException("Incorrect OTP code");
        }

        // OTP verified — delete the entry before creating the user session
        otpRepository.deleteByEmailAndOrgId(email, orgId);

        // Find or create user.
        Organization org = inv.getOrganization();
        userRepository.insertIfAbsent(
                UUID.randomUUID(), org.getId(),
                email, email.split("@")[0],
                UserRole.MEMBER.name(), Instant.now());
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("User row missing after conflict-safe insert"));

        // Create or reactivate org membership
        OrgMember member = memberRepository.findByOrganizationIdAndUserId(org.getId(), user.getId())
                .orElseGet(() -> OrgMember.builder()
                        .organization(org).user(user)
                        .invitedBy(inv.getInvitedBy())
                        .build());
        member.setRole(inv.getRole());
        member.setInviteStatus(InviteStatus.ACCEPTED);
        memberRepository.save(member);

        // Clear any prior revocation so a re-invited user can access the org again
        tokenRevocationService.clearRevocationForUserInOrg(user.getId(), org.getId());

        if (user.getOnboardingCompletedAt() == null) {
            user.setOnboardingCompletedAt(Instant.now());
            userRepository.save(user);
        }

        // Mark invite as used
        inv.setUsedAt(Instant.now());
        invitationRepository.save(inv);

        // Issue JWT scoped to the invited org
        String jwt = jwtTokenProvider.generateToken(user.getId(), org.getId(), user.getEmail(),
                member.getRole().name());

        log.info("Invite accepted: user {} joined org {} as {}", user.getEmail(), org.getId(), member.getRole());
        return Map.of(
                "token", jwt,
                "orgId",  org.getId().toString(),
                "email",  user.getEmail(),
                "role",   member.getRole().name()
        );
    }

    // -- Email sending (delegated to EmailDispatchService) ---------------------

    /**
     * Delegates to EmailDispatchService so @Async proxy interception is honoured.
     * Called by TeamService when a new invitation is created.
     */
    public void sendInviteEmail(String toEmail, String orgName, String inviterName,
                                String inviteLink, OrgMemberRole role) {
        emailDispatchService.sendInviteEmail(toEmail, orgName, inviterName, inviteLink, role);
    }

    /**
     * Scheduled cleanup: delete expired OTP rows every 5 minutes.
     * ShedLock ensures only one replica runs this at a time.
     */
    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "InvitationService_cleanupExpiredOtps",
                   lockAtMostFor = "PT4M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanupExpiredOtps() {
        otpRepository.deleteExpired(Instant.now());
        log.debug("Expired invitation OTP rows cleaned up");
    }

    @Scheduled(fixedDelay = 86_400_000)
    @Transactional
    public void purgeExpiredInvitations() {
        invitationRepository.deleteByExpiresAtBeforeAndUsedAtIsNull(Instant.now());
        log.debug("Purged expired unused invitations");
    }

    // -- Private helpers -------------------------------------------------------

    private void saveOtp(String email, UUID orgId, String plainOtp, int resendCount) {
        InvitationOtp otp = InvitationOtp.builder()
                .email(email)
                .orgId(orgId)
                .otpHash(passwordEncoder.encode(plainOtp))
                .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
                .sentAt(Instant.now())
                .resendCount(resendCount)
                .attempts(0)
                .build();
        otpRepository.save(otp);
    }

    private String generateOtp() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }
}
