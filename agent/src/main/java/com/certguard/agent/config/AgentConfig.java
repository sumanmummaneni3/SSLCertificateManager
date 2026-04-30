package com.certguard.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    private final Properties props = new Properties();
    private final Path configPath;

    public AgentConfig() {
        // Resolution order:
        // 1. -Dconfig=/path/to/application.properties  (explicit system property)
        // 2. -Dspring.config.location=file:/path/...   (Spring-style, for compatibility)
        // 3. ./application.properties                  (next to JAR / working directory)
        // 4. classpath:application.properties          (bundled in JAR — dev fallback)

        String sysProp = System.getProperty("config");
        String springProp = System.getProperty("spring.config.location");

        Path resolved = null;

        if (sysProp != null && !sysProp.isBlank()) {
            resolved = Paths.get(sysProp);
            log.info("Config: -Dconfig={}", resolved.toAbsolutePath());
        } else if (springProp != null && !springProp.isBlank()) {
            // strip "file:" prefix if present
            String path = springProp.startsWith("file:") ? springProp.substring(5) : springProp;
            resolved = Paths.get(path);
            log.info("Config: -Dspring.config.location={}", resolved.toAbsolutePath());
        } else {
            Path local = Paths.get("application.properties");
            if (Files.exists(local)) {
                resolved = local;
                log.info("Config: {}", local.toAbsolutePath());
            }
        }

        if (resolved != null && Files.exists(resolved)) {
            this.configPath = resolved;
        } else {
            this.configPath = null;
            log.info("Config: classpath:application.properties (fallback)");
        }

        reload();
    }

    public void reload() {
        props.clear();
        if (configPath != null) {
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
                log.debug("Config loaded from: {}", configPath.toAbsolutePath());
            } catch (IOException e) {
                throw new RuntimeException("Cannot read config: " + configPath, e);
            }
        } else {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream("application.properties")) {
                if (in == null) throw new RuntimeException("application.properties not found on classpath");
                props.load(in);
            } catch (IOException e) {
                throw new RuntimeException("Cannot read classpath config", e);
            }
        }
    }

    public void set(String key, String value) throws IOException {
        if (configPath == null) {
            log.warn("No on-disk config — cannot persist key: {}", key);
            return;
        }
        props.setProperty(key, value);
        List<String> lines = Files.readAllLines(configPath);
        List<String> updated = lines.stream().map(line -> {
            if (line.startsWith(key + "=") || line.startsWith(key + " ="))
                return key + "=" + value;
            return line;
        }).collect(Collectors.toList());
        Files.write(configPath, updated);
        log.debug("Config updated: {}={}", key, value.isEmpty() ? "<cleared>" : "<set>");
    }

    public String serverUrl()             { return require("certguard.server.url"); }
    public String serverCertFingerprint() { return props.getProperty("certguard.server.cert-fingerprint", ""); }
    public boolean trustSelfSigned()      { return bool("certguard.server.trust-self-signed", true); }

    public String registrationToken()    { return props.getProperty("certguard.registration.token", ""); }
    public String registrationOrgId()    { return props.getProperty("certguard.registration.org-id", ""); }

    public String agentId()              { return props.getProperty("certguard.agent.id", ""); }
    public String agentKey()             { return props.getProperty("certguard.agent.key", ""); }
    public String agentName()            { return props.getProperty("certguard.agent.name", "certguard-agent"); }
    public String agentCertPath()        { return props.getProperty("certguard.agent.cert-path", "./certguard-agent-client.pem"); }

    public boolean isRegistered() {
        boolean registered = !agentId().isBlank() && !agentKey().isBlank();
        log.info("isRegistered={} agentId='{}' agentKey='{}'",
                registered,
                agentId().isBlank() ? "<blank>" : agentId().substring(0, Math.min(8, agentId().length())) + "...",
                agentKey().isBlank() ? "<blank>" : "<set>");
        return registered;
    }

    public List<String> allowedCidrs() {
        String raw = props.getProperty("certguard.agent.allowed-cidrs", "");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    public int maxTargets()          { return integer("certguard.agent.max-targets", 50); }
    public int pollIntervalSeconds() { return integer("certguard.agent.poll-interval-seconds", 30); }
    public int scanTimeoutSeconds()  { return integer("certguard.agent.scan-timeout-seconds", 10); }
    public int scanThreads()         { return integer("certguard.agent.scan-threads", 5); }

    private String require(String key) {
        String val = props.getProperty(key);
        if (val == null || val.isBlank())
            throw new RuntimeException("Required config missing: " + key);
        return val.trim();
    }

    private int integer(String key, int def) {
        try { return Integer.parseInt(props.getProperty(key, String.valueOf(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private boolean bool(String key, boolean def) {
        String v = props.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }
}
