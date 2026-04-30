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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvitationService {

    private final InvitationRepository invitationRepository;
    private final OrgMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${spring.mail.from:noreply@certguard.cloud}")
    private String fromAddress;

    @Value("${app.dev-mode:true}")
    private boolean devMode;

    /**
     * In-memory OTP store: tokenHash → {otp, expiresAt}
     * In production this should be Redis. For now a ConcurrentHashMap is
     * sufficient since the OTP window is 10 minutes.
     */
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();

    private record OtpEntry(String otp, Instant expiresAt) {}

    // ── Step 1: validate invite token and send OTP ─────────────────────────

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

        sendOtpEmail(inv.getEmail(), otp, inv.getOrganization().getName());

        log.info("OTP sent for invite {} to {}", inv.getId(), inv.getEmail());
        return inv.getEmail();
    }

    // ── Step 2: verify OTP, create user + membership, issue JWT ─────────────

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

    // ── Email sending ─────────────────────────────────────────────────────

    @Async
    public void sendInviteEmail(String toEmail, String orgName, String inviterName,
                                String inviteLink, OrgMemberRole role) {
        if (devMode) {
            log.info("[DEV] Invite email to {} — link: {}", toEmail, inviteLink);
            return;
        }
        Context ctx = new Context();
        ctx.setVariable("orgName", orgName);
        ctx.setVariable("inviterName", inviterName);
        ctx.setVariable("inviteLink", inviteLink);
        ctx.setVariable("role", role.name());
        sendMimeEmail(toEmail,
                "You've been invited to " + orgName + " on CertGuard Cloud",
                "invite", ctx);
    }

    @Async
    void sendOtpEmail(String toEmail, String otp, String orgName) {
        if (devMode) {
            log.info("[DEV] OTP for {} joining {}: {}", toEmail, orgName, otp);
            return;
        }
        Context ctx = new Context();
        ctx.setVariable("otp", otp);
        ctx.setVariable("orgName", orgName);
        sendMimeEmail(toEmail, "Your CertGuard sign-in code: " + otp, "otp", ctx);
    }

    private void sendMimeEmail(String to, String subject, String templateName, Context ctx) {
        try {
            String htmlBody = templateEngine.process("email/" + templateName, ctx);
            String textBody = templateEngine.process("email/" + templateName + ".txt", ctx);
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(textBody, htmlBody);
            mailSender.send(msg);
        } catch (Exception e) {
            log.error("Failed to send email template={} to {}: {}", templateName, to, e.getMessage());
        }
    }

    private String generateOtp() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }
}
