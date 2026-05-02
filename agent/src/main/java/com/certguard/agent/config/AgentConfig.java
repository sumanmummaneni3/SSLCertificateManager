package com.certguard.agent.config;

import com.certguard.agent.security.BundleUnsealer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    private final Properties props = new Properties();
    private final Path configPath;
    private final String[] cliArgs;

    /** Constructs config without CLI args (backwards-compatible constructor). */
    public AgentConfig() {
        this(new String[0]);
    }

    public AgentConfig(String[] cliArgs) {
        this.cliArgs = cliArgs;
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

        // If agent.id is absent and a bundle file is present, unseal the bundle
        // and bootstrap application.properties from the decrypted config.
        if (agentId().isBlank()) {
            tryUnsealBundle();
        }
    }

    /**
     * If a bundle.cgb is detectable (via CLI arg, env var, or ./bundle.cgb),
     * decrypts it using BundleUnsealer, merges the result into application.properties,
     * and reloads config so the registration flow can proceed normally.
     */
    private void tryUnsealBundle() {
        BundleUnsealer unsealer = new BundleUnsealer(cliArgs);
        Path bundlePath = unsealer.resolveBundlePath();

        if (!Files.exists(bundlePath)) {
            // No bundle present — normal startup without bundle
            return;
        }

        log.info("Bundle file detected at {} — decrypting...", bundlePath.toAbsolutePath());
        try {
            Map<String, String> bundleConfig = unsealer.unseal();

            // Map bundle config keys to application.properties keys
            // Bundle JSON keys: agentId, orgId, serverUrl, registrationToken, agentName,
            //                   allowedCidrs, maxTargets
            if (bundleConfig.containsKey("serverUrl")) {
                props.setProperty("certguard.server.url", bundleConfig.get("serverUrl"));
            }
            if (bundleConfig.containsKey("registrationToken")) {
                props.setProperty("certguard.registration.token", bundleConfig.get("registrationToken"));
            }
            if (bundleConfig.containsKey("orgId")) {
                props.setProperty("certguard.registration.org-id", bundleConfig.get("orgId"));
            }
            if (bundleConfig.containsKey("agentName")) {
                props.setProperty("certguard.agent.name", bundleConfig.get("agentName"));
            }
            if (bundleConfig.containsKey("allowedCidrs")) {
                props.setProperty("certguard.agent.allowed-cidrs", bundleConfig.get("allowedCidrs"));
            }
            if (bundleConfig.containsKey("maxTargets")) {
                props.setProperty("certguard.agent.max-targets", bundleConfig.get("maxTargets"));
            }

            // Persist to application.properties (atomic write, chmod 0600)
            persistAndSecure();

            log.info("Bundle config merged into application.properties — agent will register on startup.");
        } catch (Exception e) {
            log.error("Failed to unseal bundle: {}", e.getMessage(), e);
            // Let the agent proceed; if registration is missing it will fail with a clear error
        }
    }

    /**
     * Atomically writes current props to configPath, then sets POSIX permissions 0600.
     */
    private void persistAndSecure() throws IOException {
        if (configPath == null) {
            log.warn("No on-disk config path — bundle config cannot be persisted");
            return;
        }
        // Write to a temp file first, then rename (atomic on POSIX)
        Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(tmp, StandardCharsets.ISO_8859_1)) {
            props.store(writer, "CertGuard Agent — written by BundleUnsealer");
        }
        Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.setPosixFilePermissions(configPath,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            log.warn("Could not set file permissions on {} (non-POSIX filesystem)", configPath);
        }
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
        // Always update in-memory state first, regardless of whether we can persist.
        // This ensures agentId()/agentKey() return the correct values after registration
        // even when configPath is null (classpath fallback).
        props.setProperty(key, value);
        if (configPath == null) {
            log.warn("No on-disk config — key '{}' updated in-memory only (will not survive restart)", key);
            return;
        }
        List<String> lines = Files.readAllLines(configPath);
        boolean found = lines.stream().anyMatch(l -> l.startsWith(key + "=") || l.startsWith(key + " ="));
        List<String> updated = lines.stream().map(line -> {
            if (line.startsWith(key + "=") || line.startsWith(key + " ="))
                return key + "=" + value;
            return line;
        }).collect(Collectors.toList());
        if (!found) {
            updated.add(key + "=" + value);
        }
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
