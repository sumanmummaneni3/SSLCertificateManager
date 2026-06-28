package com.certguard.agent;

import com.certguard.agent.config.AgentConfig;
import com.certguard.agent.config.AgentMode;
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
 *   2. Build secure HTTP client (TLS 1.3, cert fingerprint pinning)
 *   3. If not yet registered → register with one-time token
 *   4. Start poll loop (heartbeat + scan jobs on fixed interval)
 *   5. Block until shutdown signal
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) throws Exception {
        // Handle --help before anything else
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                return;
            }
        }

        log.info("===========================================");
        log.info("  CertGuard Agent v1.0 starting");
        log.info("===========================================");

        // 1. Config — pass CLI args so BundleUnsealer can read --bundle / --install-key
        AgentConfig config = new AgentConfig(args);

        // 2. Build initial HTTP client (pre-registration — no client cert yet)
        SecureHttpClient clientFactory = new SecureHttpClient(config);
        CloseableHttpClient http = clientFactory.build();
        ServerApiClient api = new ServerApiClient(config, http);

        // 3. Register if not already done (skip for ANONYMOUS mode — no registration needed)
        if (config.getMode() == AgentMode.ANONYMOUS) {
            log.info("ANONYMOUS mode — skipping registration, using scan token");
        } else if (!config.isRegistered()) {
            log.info("Agent not registered — starting registration...");
            RegistrationService registration = new RegistrationService(config, api);
            registration.register();
        } else {
            log.info("Agent already registered — ID: {}", config.agentId());
        }

        // 4. Start poll loop
        SslScanner scanner  = new SslScanner();
        PollLoop   pollLoop = new PollLoop(config, api, scanner);
        pollLoop.start();

        // 5. Shutdown hook — clean stop on SIGTERM / Ctrl-C
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

    private static void printHelp() {
        System.out.println("CertGuard Agent v1.0");
        System.out.println();
        System.out.println("Usage: java -jar certguard-agent.jar [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --bundle <path>        Path to the encrypted bundle file (bundle.cgb).");
        System.out.println("                         Default: ./bundle.cgb");
        System.out.println("                         Env var: CERTGUARD_BUNDLE_PATH");
        System.out.println();
        System.out.println("  --install-key <key>    Plaintext install key (CGK-...) for decrypting the bundle.");
        System.out.println("                         WARNING: prefer the env var or interactive prompt.");
        System.out.println("                         Env var: CERTGUARD_INSTALL_KEY");
        System.out.println();
        System.out.println("  --install-key-file <path>");
        System.out.println("                         Path to a file containing the install key (one key per line).");
        System.out.println("                         Useful in automated deployments with secrets mounted as files.");
        System.out.println();
        System.out.println("  --help, -h             Show this help message and exit.");
        System.out.println();
        System.out.println("Config file resolution order:");
        System.out.println("  1. -Dconfig=/path/to/application.properties");
        System.out.println("  2. -Dspring.config.location=file:/path/...");
        System.out.println("  3. ./application.properties (next to JAR)");
        System.out.println("  4. classpath:application.properties (bundled in JAR)");
        System.out.println();
        System.out.println("Bundle first-run flow:");
        System.out.println("  If agent.id is not set and bundle.cgb is found, the agent decrypts the");
        System.out.println("  bundle, writes credentials to application.properties, and registers.");
    }
}
