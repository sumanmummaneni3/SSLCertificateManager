package com.certguard.auth.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${auth.mail.from:noreply@certguard.cloud}")
    private String from;

    @Value("${auth.mail.from-name:CertGuard}")
    private String fromName;

    @Value("${auth.callback.base-url}")
    private String baseUrl;

    public MailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Async
    public void sendVerificationEmail(String to, String token) {
        String link = baseUrl + "/auth/verify-email?token=" + token;
        String subject = "Verify your CertGuard account";
        String body = """
                <html><body style="font-family:sans-serif;color:#1a1a1a;max-width:560px;margin:0 auto;padding:24px">
                  <h2 style="color:#1d4ed8">Welcome to CertGuard</h2>
                  <p>Thanks for signing up. Please verify your email address to activate your account.</p>
                  <p>
                    <a href="%s"
                       style="display:inline-block;padding:12px 24px;background:#1d4ed8;color:#fff;
                              text-decoration:none;border-radius:6px;font-weight:600">
                      Verify email address
                    </a>
                  </p>
                  <p style="color:#6b7280;font-size:13px">This link expires in 24 hours.<br>
                  If you didn't create a CertGuard account you can safely ignore this email.</p>
                  <p style="color:#6b7280;font-size:13px">Or copy this link:<br>%s</p>
                </body></html>
                """.formatted(link, link);
        send(to, subject, body);
    }

    @Async
    public void sendPasswordResetEmail(String to, String token) {
        String link = baseUrl + "/auth/reset-password?token=" + token;
        String subject = "Reset your CertGuard password";
        String body = """
                <html><body style="font-family:sans-serif;color:#1a1a1a;max-width:560px;margin:0 auto;padding:24px">
                  <h2 style="color:#1d4ed8">Password reset request</h2>
                  <p>We received a request to reset the password for this CertGuard account.</p>
                  <p>
                    <a href="%s"
                       style="display:inline-block;padding:12px 24px;background:#1d4ed8;color:#fff;
                              text-decoration:none;border-radius:6px;font-weight:600">
                      Reset password
                    </a>
                  </p>
                  <p style="color:#6b7280;font-size:13px">This link expires in 1 hour.<br>
                  If you didn't request a password reset you can safely ignore this email —
                  your password has not been changed.</p>
                  <p style="color:#6b7280;font-size:13px">Or copy this link:<br>%s</p>
                </body></html>
                """.formatted(link, link);
        send(to, subject, body);
    }

    private void send(String to, String subject, String htmlBody) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(msg);
            log.debug("Email '{}' sent to {}", subject, to);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send email '{}' to {}: {}", subject, to, e.getMessage());
        }
    }
}
