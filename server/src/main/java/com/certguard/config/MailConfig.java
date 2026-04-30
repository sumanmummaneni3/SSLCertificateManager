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
    private final String mailUsername;
    private final String mailPassword;

    public MailConfig(
            @Value("${app.dev-mode:false}") boolean devMode,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${spring.mail.password:}") String mailPassword) {
        this.devMode = devMode;
        this.mailUsername = mailUsername;
        this.mailPassword = mailPassword;
    }

    @PostConstruct
    public void validate() {
        if (devMode) {
            log.warn("app.dev-mode=true — mail credential validation skipped, emails will be logged only");
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
