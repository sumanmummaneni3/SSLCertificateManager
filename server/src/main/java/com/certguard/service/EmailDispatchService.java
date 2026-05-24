package com.certguard.service;

import com.certguard.enums.OrgMemberRole;
import com.certguard.exception.EmailDeliveryException;
import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Handles async dispatch of transactional emails for the invitation and OTP flows.
 *
 * Extracted from InvitationService so that Spring AOP can intercept the @Async proxy
 * correctly — self-invocation on the same bean bypasses AOP proxies and silently
 * makes @Async synchronous.
 *
 * When devMode=false and SMTP fails, the exception is re-thrown as a RuntimeException
 * so the caller can surface a 500 or schedule a retry. In devMode=true the failure
 * path is a deliberate no-op (email sending is suppressed entirely in dev).
 */
@Service
public class EmailDispatchService {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchService.class);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.mail.from:noreply@certguard.cloud}")
    private String fromAddress;

    @Value("${app.dev-mode:true}")
    private boolean devMode;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    public EmailDispatchService(JavaMailSender mailSender, TemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    @PostConstruct
    void warnIfDevMode() {
        if (devMode) {
            log.warn("*** EMAIL DISPATCH IS IN DEV MODE — invite and OTP emails will NOT be sent ***");
        }
    }

    /**
     * Sends the org-invitation email asynchronously.
     *
     * @param toEmail      recipient address
     * @param orgName      name of the organisation the user is being invited to
     * @param inviterName  display name of the user who sent the invite
     * @param inviteLink   full URL the recipient should visit to accept
     * @param role         the role the invitee will receive
     */
    @Async
    public void sendInviteEmail(String toEmail, String orgName, String inviterName,
                                String inviteLink, OrgMemberRole role) {
        if (devMode) {
            log.warn("[DEV] Invite email suppressed — to={} link={}", toEmail, inviteLink);
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

    /**
     * Sends the OTP sign-in code email asynchronously.
     *
     * @param toEmail  recipient address (the invited user)
     * @param otp      6-digit OTP code
     * @param orgName  organisation name, for display in the email body
     */
    @Async
    public void sendOtpEmail(String toEmail, String otp, String orgName) {
        if (devMode) {
            log.warn("[DEV] OTP email suppressed — to={} org={} otp={}", toEmail, orgName, otp);
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
            // Re-throw in production so the caller can surface a 500 or schedule a retry.
            // In dev mode the send path is never reached (guard above), so this branch
            // only executes when devMode=false.
            throw new EmailDeliveryException(
                    "Failed to send " + templateName + " email to " + to, e);
        }
    }
}
