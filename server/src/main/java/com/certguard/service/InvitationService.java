package com.certguard.service;

import com.certguard.dto.response.InvitationResponse;
import com.certguard.entity.*;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.UserRole;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
import com.certguard.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.scheduling.annotation.Scheduled;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final int MAX_OTP_ATTEMPTS  = 5;
    private static final int MAX_OTP_RESENDS   = 3;
    private static final long OTP_COOLDOWN_SECONDS = 60;

    /**
     * In-memory OTP store: tokenHash -> {otp, expiresAt, attempts, sentAt, resendCount}
     * In production this should be Redis. For now a ConcurrentHashMap is
     * sufficient since the OTP window is 10 minutes.
     */
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();

    private record OtpEntry(String otp, Instant expiresAt, int attempts, Instant sentAt, int resendCount) {}

    public InvitationService(InvitationRepository invitationRepository,
                             OrgMemberRepository memberRepository,
                             UserRepository userRepository,
                             JwtTokenProvider jwtTokenProvider,
                             EmailDispatchService emailDispatchService,
                             TokenRevocationService tokenRevocationService) {
        this.invitationRepository   = invitationRepository;
        this.memberRepository       = memberRepository;
        this.userRepository         = userRepository;
        this.jwtTokenProvider       = jwtTokenProvider;
        this.emailDispatchService   = emailDispatchService;
        this.tokenRevocationService = tokenRevocationService;
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

        OtpEntry existing = otpStore.get(hash);
        if (existing != null && !existing.expiresAt().isBefore(Instant.now())) {
            // An unexpired OTP already exists — enforce cool-down and resend cap to
            // prevent unlimited email-bombing of the invitee.
            long secondsSinceSent = ChronoUnit.SECONDS.between(existing.sentAt(), Instant.now());
            if (secondsSinceSent < OTP_COOLDOWN_SECONDS) {
                throw new IllegalArgumentException(
                        "Please wait " + (OTP_COOLDOWN_SECONDS - secondsSinceSent) + " seconds before requesting another code");
            }
            if (existing.resendCount() >= MAX_OTP_RESENDS) {
                throw new IllegalArgumentException(
                        "Too many codes requested — please ask the admin to send a new invitation");
            }
            // Resend allowed: generate a fresh code with a fresh 10-min window
            String otp = generateOtp();
            otpStore.put(hash, new OtpEntry(otp, Instant.now().plus(10, ChronoUnit.MINUTES),
                    0, Instant.now(), existing.resendCount() + 1));
            emailDispatchService.sendOtpEmail(inv.getEmail(), otp, inv.getOrganization().getName());
            log.info("OTP resent ({}/{}) for invite {} to {}",
                    existing.resendCount() + 1, MAX_OTP_RESENDS, inv.getId(), inv.getEmail());
        } else {
            // No unexpired OTP — generate fresh one
            String otp = generateOtp();
            otpStore.put(hash, new OtpEntry(otp, Instant.now().plus(10, ChronoUnit.MINUTES),
                    0, Instant.now(), 0));
            emailDispatchService.sendOtpEmail(inv.getEmail(), otp, inv.getOrganization().getName());
            log.info("OTP sent for invite {} to {}", inv.getId(), inv.getEmail());
        }
        return inv.getEmail();
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

        OtpEntry entry = otpStore.get(hash);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            otpStore.remove(hash);
            throw new IllegalArgumentException("OTP has expired — please request a new one");
        }
        if (!entry.otp().equals(submittedOtp.trim())) {
            int attempts = entry.attempts() + 1;
            if (attempts >= MAX_OTP_ATTEMPTS) {
                otpStore.remove(hash);
                throw new IllegalArgumentException("Too many incorrect attempts — please request a new OTP");
            }
            otpStore.put(hash, new OtpEntry(entry.otp(), entry.expiresAt(), attempts, entry.sentAt(), entry.resendCount()));
            throw new IllegalArgumentException("Incorrect OTP code");
        }

        // Clean up OTP
        otpStore.remove(hash);

        // Find or create user.  ON CONFLICT DO NOTHING makes this safe if a concurrent
        // Google OAuth provisioning races this accept — whichever writer wins, the
        // re-fetch below returns the committed row with no exception thrown.
        // users.org_id points at the invited org so AuthProvisioningService resolves
        // the correct org context on subsequent logins without needing a placeholder.
        Organization org = inv.getOrganization();
        userRepository.insertIfAbsent(
                UUID.randomUUID(), org.getId(),
                inv.getEmail(), inv.getEmail().split("@")[0],
                UserRole.MEMBER.name(), Instant.now());
        User user = userRepository.findByEmail(inv.getEmail())
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

    @Scheduled(fixedDelay = 300_000)
    public void evictExpiredOtps() {
        otpStore.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(Instant.now()));
    }

    @Scheduled(fixedDelay = 86_400_000)
    @Transactional
    public void purgeExpiredInvitations() {
        invitationRepository.deleteByExpiresAtBeforeAndUsedAtIsNull(Instant.now());
        log.debug("Purged expired unused invitations");
    }

    private String generateOtp() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }
}
