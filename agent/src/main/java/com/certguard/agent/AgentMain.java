package com.certguard.agent;

import com.certguard.agent.config.AgentConfig;
import com.certguard.agent.http.*;
import com.certguard.agent.scanner.SslScanner;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CertGuard Agent — plain Java entry point.
 *
 * No Spring, no DI framework. Everything wired manually.
 *
 * Startup sequence:
 *   1. Load config from application.properties
 *   2. Build secure HTTP client (TLS 1.3, cert pinning, mTLS)
 *   3. If not yet registered → register with one-time token
 *   4. Rebuild HTTP client with mTLS client cert (now available post-registration)
 *   5. Start poll loop (heartbeat + scan jobs on fixed interval)
 *   6. Block until shutdown signal
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) throws Exception {
        log.info("===========================================");
        log.info("  CertGuard Agent v1.0 starting");
        log.info("===========================================");

        // 1. Config
        AgentConfig config = new AgentConfig();

        // 2. Build initial HTTP client (pre-registration — no client cert yet)
        SecureHttpClient clientFactory = new SecureHttpClient(config);
        CloseableHttpClient http = clientFactory.build();
        ServerApiClient api = new ServerApiClient(config, http);

        // 3. Register if not already done
        if (!config.isRegistered()) {
            log.info("Agent not registered — starting registration...");
            RegistrationService registration = new RegistrationService(config, api);
            registration.register();

            // 4. Rebuild HTTP client now that we have the mTLS client cert
            http.close();
            http = clientFactory.build();
            api  = new ServerApiClient(config, http);
            log.info("HTTP client rebuilt with mTLS client certificate");
        } else {
            log.info("Agent already registered — ID: {}", config.agentId());
        }

        // 5. Start poll loop
        SslScanner scanner  = new SslScanner();
        PollLoop   pollLoop = new PollLoop(config, api, scanner);
        pollLoop.start();

        // 6. Shutdown hook — clean stop on SIGTERM / Ctrl-C
        final CloseableHttpClient finalHttp = http;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — stopping agent...");
            pollLoop.stop();
            try { finalHttp.close(); } catch (Throwable ignored) {}
            log.info("Agent stopped.");
        }, "certguard-shutdown"));

        log.info("Agent running — polling every {}s. Press Ctrl-C to stop.",
                config.pollIntervalSeconds());

        // Block main thread
        Thread.currentThread().join();
    }
}
