package com.certguard.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-fast validator for mail credentials when not running in dev-mode.
 * In dev-mode, emails are only logged; credentials are not required.
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    private final boolean devMode;
    private final boolean smtpAuth;
    private final String mailHost;
    private final String mailUsername;
    private final String mailPassword;

    public MailConfig(
            @Value("${app.dev-mode:false}") boolean devMode,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean smtpAuth,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${spring.mail.password:}") String mailPassword) {
        this.devMode = devMode;
        this.smtpAuth = smtpAuth;
        this.mailHost = mailHost;
        this.mailUsername = mailUsername;
        this.mailPassword = mailPassword;
    }

    @PostConstruct
    public void validate() {
        if (devMode) {
            log.warn("app.dev-mode=true — mail credential validation skipped, emails will be logged only");
            return;
        }
        if (!smtpAuth) {
            // No-AUTH submission to a trusted internal MTA (e.g. the self-hosted
            // `mailserver` service on the docker network). Credentials are meaningless
            // here, but a host is still mandatory — an empty host would fail at send
            // time with a far less obvious error.
            if (mailHost == null || mailHost.isBlank()) {
                throw new IllegalStateException(
                    "MAIL_HOST is required when SMTP AUTH is disabled (MAIL_SMTP_AUTH=false). " +
                    "Point it at the internal mailserver service.");
            }
            log.info("SMTP AUTH disabled — submitting unauthenticated to internal MTA at {}. " +
                    "Credential validation skipped.", mailHost);
            return;
        }
        if (mailUsername == null || mailUsername.isBlank()) {
            throw new IllegalStateException(
                "MAIL_USERNAME is required when app.dev-mode=false. " +
                "Set the MAIL_USERNAME environment variable.");
        }
        if (mailPassword == null || mailPassword.isBlank()) {
            throw new IllegalStateException(
                "MAIL_PASSWORD is required when app.dev-mode=false. " +
                "Set the MAIL_PASSWORD environment variable.");
        }
        log.info("Mail credentials validated for username: {}", mailUsername);
    }
}
