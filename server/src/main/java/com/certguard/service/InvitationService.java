package com.certguard.service;

import com.certguard.dto.response.InvitationResponse;
import com.certguard.entity.*;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.SubscriptionStatus;
import com.certguard.enums.UserRole;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
import com.certguard.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final EmailDispatchService emailDispatchService;

    /**
     * In-memory OTP store: tokenHash -> {otp, expiresAt}
     * In production this should be Redis. For now a ConcurrentHashMap is
     * sufficient since the OTP window is 10 minutes.
     */
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();

    private record OtpEntry(String otp, Instant expiresAt) {}

    public InvitationService(InvitationRepository invitationRepository,
                             OrgMemberRepository memberRepository,
                             UserRepository userRepository,
                             OrganizationRepository orgRepository,
                             SubscriptionRepository subscriptionRepository,
                             JwtTokenProvider jwtTokenProvider,
                             EmailDispatchService emailDispatchService) {
        this.invitationRepository = invitationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.emailDispatchService = emailDispatchService;
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

        // Generate a 6-digit OTP, valid 10 minutes
        String otp = generateOtp();
        otpStore.put(hash, new OtpEntry(otp, Instant.now().plus(10, ChronoUnit.MINUTES)));

        // Dispatch via the injected bean so Spring AOP @Async proxy is honoured
        emailDispatchService.sendOtpEmail(inv.getEmail(), otp, inv.getOrganization().getName());

        log.info("OTP sent for invite {} to {}", inv.getId(), inv.getEmail());
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
        if (entry == null || entry.expiresAt().isBefore(Instant.now()))
            throw new IllegalArgumentException("OTP has expired — please request a new one");
        if (!entry.otp().equals(submittedOtp.trim()))
            throw new IllegalArgumentException("Incorrect OTP code");

        // Clean up OTP
        otpStore.remove(hash);

        // Find or create user
        Organization org = inv.getOrganization();
        User user = userRepository.findByEmail(inv.getEmail()).orElseGet(() -> {
            // Create a placeholder org for this user — they will belong to the invited org
            // via org_members. The placeholder org has a zero quota.
            Organization placeholder = Organization.builder()
                    .name(inv.getEmail().split("@")[0] + "'s Account")
                    .build();
            orgRepository.save(placeholder);
            subscriptionRepository.save(Subscription.builder()
                    .organization(placeholder).maxCertificateQuota(0)
                    .status(SubscriptionStatus.TRIAL).build());
            return userRepository.save(User.builder()
                    .organization(placeholder)
                    .email(inv.getEmail())
                    .name(inv.getEmail().split("@")[0])
                    .role(UserRole.MEMBER)
                    .build());
        });

        // Create or reactivate org membership
        OrgMember member = memberRepository.findByOrganizationIdAndUserId(org.getId(), user.getId())
                .orElseGet(() -> OrgMember.builder()
                        .organization(org).user(user)
                        .invitedBy(inv.getInvitedBy())
                        .build());
        member.setRole(inv.getRole());
        member.setInviteStatus(InviteStatus.ACCEPTED);
        memberRepository.save(member);

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

    private String generateOtp() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }
}
