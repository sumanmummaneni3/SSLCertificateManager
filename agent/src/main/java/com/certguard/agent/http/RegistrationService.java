package com.certguard.agent.http;

import com.certguard.agent.config.AgentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Handles the one-time registration flow:
 * 1. POST registration token to server
 * 2. Receive agentId, agentKey, clientCertPem
 * 3. Save client cert PEM to disk
 * 4. Persist agentId + agentKey to application.properties
 * 5. Clear the registration token from config
 */
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final AgentConfig     config;
    private final ServerApiClient api;

    public RegistrationService(AgentConfig config, ServerApiClient api) {
        this.config = config;
        this.api    = api;
    }

    public void register() throws Exception {
        String token = config.registrationToken();
        String orgId = config.registrationOrgId();

        if (token.isBlank()) {
            throw new IllegalStateException(
                "certguard.registration.token is not set in application.properties");
        }
        if (orgId.isBlank()) {
            throw new IllegalStateException(
                "certguard.registration.org-id is not set in application.properties");
        }

        log.info("Registering agent '{}' with server {}...", config.agentName(), config.serverUrl());

        JsonNode resp = api.register(token, orgId);

        String agentId      = resp.get("id").asText();
        String agentKey     = resp.get("agentKey").asText();
        String clientCertPem = resp.get("clientCertPem").asText();

        // Save mTLS client cert — chmod 600
        Path certPath = Path.of(config.agentCertPath());
        Files.writeString(certPath, clientCertPem);
        try {
            Files.setPosixFilePermissions(certPath,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows — skip chmod
        }
        log.info("Client certificate saved to {}", certPath.toAbsolutePath());

        // Persist credentials and clear the one-time token
        config.set("certguard.agent.id",           agentId);
        config.set("certguard.agent.key",           agentKey);
        config.set("certguard.registration.token",  "");   // burn the token

        // Reload config so agentId() and agentKey() return the new values
        config.reload();

        log.info("=========================================");
        log.info("  Registration successful!");
        log.info("  Agent ID : {}", agentId);
        log.info("  Cert     : {}", certPath.toAbsolutePath());
        log.info("  Token    : cleared");
        log.info("=========================================");
    }
}
