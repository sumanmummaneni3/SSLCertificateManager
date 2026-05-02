package com.certguard.agent.http;

import com.certguard.agent.config.AgentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the one-time registration flow:
 * 1. POST registration token to server
 * 2. Receive agentId and agentKey
 * 3. Persist agentId + agentKey to application.properties
 * 4. Clear the registration token from config
 *
 * mTLS client certificates are no longer issued or stored.
 * Authentication uses bearer agentKey + HMAC signing.
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

        String agentId  = resp.get("id").asText();
        String agentKey = resp.get("agentKey").asText();

        // Persist credentials to disk and update in-memory state.
        // config.set() always updates props in-memory first, then writes to disk
        // if configPath is set — so agentId()/agentKey() are immediately correct
        // without needing a reload().
        config.set("certguard.agent.id",           agentId);
        config.set("certguard.agent.key",           agentKey);
        config.set("certguard.registration.token",  "");   // burn the token

        log.info("=========================================");
        log.info("  Registration successful!");
        log.info("  Agent ID : {}", agentId);
        log.info("  Token    : cleared");
        log.info("=========================================");
    }
}
