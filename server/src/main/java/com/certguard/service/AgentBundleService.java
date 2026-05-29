package com.certguard.service;

import com.certguard.dto.IssueBundleResult;
import com.certguard.dto.request.CreateAgentRequest;
import com.certguard.entity.*;
import com.certguard.enums.AgentStatus;
import com.certguard.exception.BundleExpiredException;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.AgentInstallKeyRepository;
import com.certguard.repository.AgentRegistrationTokenRepository;
import com.certguard.repository.AgentRepository;
import com.certguard.repository.LocationRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.security.AgentBundleCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@Transactional(readOnly = true)
public class AgentBundleService {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentInstallKeyRepository installKeyRepository;
    private final AgentRepository agentRepository;
    private final AgentRegistrationTokenRepository tokenRepository;
    private final OrganizationRepository orgRepository;
    private final LocationRepository locationRepository;
    private final AgentBundleCrypto bundleCrypto;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${app.agent.bundle.download-url-ttl-seconds:3600}")
    private int downloadUrlTtlSeconds;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.agent.bundle.argon2.memory-kib:65536}")
    private int argon2MemoryKib;

    @Value("${app.agent.bundle.argon2.iterations:3}")
    private int argon2Iterations;

    @Value("${app.agent.bundle.argon2.parallelism:1}")
    private int argon2Parallelism;

    @Value("${app.agent.artifact-url-template:}")
    private String agentArtifactUrlTemplate;

    @Value("${app.release-tag:latest}")
    private String appReleaseTag;

    public AgentBundleService(AgentInstallKeyRepository installKeyRepository,
                               AgentRepository agentRepository,
                               AgentRegistrationTokenRepository tokenRepository,
                               OrganizationRepository orgRepository,
                               LocationRepository locationRepository,
                               AgentBundleCrypto bundleCrypto,
                               BCryptPasswordEncoder passwordEncoder) {
        this.installKeyRepository = installKeyRepository;
        this.agentRepository      = agentRepository;
        this.tokenRepository      = tokenRepository;
        this.orgRepository        = orgRepository;
        this.locationRepository   = locationRepository;
        this.bundleCrypto         = bundleCrypto;
        this.passwordEncoder      = passwordEncoder;
    }

    /**
     * Creates a new agent in PENDING status, generates a registration token, encrypts
     * the bootstrap config, and returns a single-use bundle download URL + install key.
     */
    @Transactional
    public IssueBundleResult issueBundle(CreateAgentRequest req, UUID orgId, UUID callerUserId)
            throws Exception {

        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));

        // 1. Create agent row in PENDING status
        Agent agent = Agent.builder()
                .organization(org)
                .name(req.getAgentName())
                .agentKeyHash("PENDING") // placeholder; overwritten at registration time
                .allowedCidrs(req.getAllowedCidrs())
                .maxTargets(req.getMaxTargets())
                .status(AgentStatus.PENDING)
                .build();
        if (req.getLocationId() != null) {
            locationRepository.findById(req.getLocationId())
                    .ifPresent(agent::setLocation);
        }
        agent = agentRepository.save(agent);

        // 2. Create registration token (reuses AgentService token format)
        String plainToken = "CGR-" + UUID.randomUUID().toString().toUpperCase();
        String tokenHash  = passwordEncoder.encode(plainToken);

        AgentRegistrationToken regToken = AgentRegistrationToken.builder()
                .organization(org)
                .tokenHash(tokenHash)
                .agentName(req.getAgentName())
                .agentId(agent.getId())   // link to pre-created agent row
                .used(false)
                .expiresAt(Instant.now().plus(downloadUrlTtlSeconds, ChronoUnit.SECONDS))
                .createdBy(callerUserId)
                .build();
        tokenRepository.save(regToken);

        // 3. Generate plaintext install key: "CGK-" + base32(25 random bytes) ~ 125 bits entropy
        String plainInstallKey = "CGK-" + generateBase32(25);

        // 4. Generate KDF salt and bundle download token
        SecureRandom rng = new SecureRandom();
        byte[] kdfSalt = new byte[16];
        rng.nextBytes(kdfSalt);

        byte[] bundleDownloadTokenBytes = new byte[32];
        rng.nextBytes(bundleDownloadTokenBytes);
        String bundleDownloadToken = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(bundleDownloadTokenBytes);

        // 5. Derive wrapping key
        byte[] wrappingKey = bundleCrypto.deriveWrappingKey(
                plainInstallKey.toCharArray(), kdfSalt,
                argon2MemoryKib, argon2Iterations, argon2Parallelism);

        // 6. Build plaintext payload JSON and encrypt
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("agentId",           agent.getId().toString());
        payloadMap.put("orgId",             orgId.toString());
        payloadMap.put("serverUrl",         baseUrl);
        payloadMap.put("registrationToken", plainToken);
        payloadMap.put("agentName",         req.getAgentName());
        payloadMap.put("allowedCidrs",      String.join(",", req.getAllowedCidrs()));
        payloadMap.put("maxTargets",        req.getMaxTargets());

        byte[] payloadBytes = OBJECT_MAPPER.writeValueAsBytes(payloadMap);
        byte[] sealedPayload = bundleCrypto.sealPayload(payloadBytes, wrappingKey, kdfSalt);

        // 7. Hash the install key (BCrypt) and bundle download token (SHA-256)
        String installKeyHash          = passwordEncoder.encode(plainInstallKey);
        String downloadTokenHash       = sha256Hex(bundleDownloadToken);

        // 8. Persist AgentInstallKey row
        Instant expiresAt = Instant.now().plus(downloadUrlTtlSeconds, ChronoUnit.SECONDS);
        AgentInstallKey installKey = AgentInstallKey.builder()
                .agent(agent)
                .orgId(orgId)
                .kdfSalt(kdfSalt)
                .kdfMemoryKib(argon2MemoryKib)
                .kdfIterations(argon2Iterations)
                .kdfParallelism(argon2Parallelism)
                .installKeyHash(installKeyHash)
                .bundleDownloadTokenHash(downloadTokenHash)
                .sealedPayload(sealedPayload)
                .expiresAt(expiresAt)
                .createdBy(callerUserId)
                .build();
        installKeyRepository.save(installKey);

        // 9. Build download URL
        String downloadUrl = baseUrl + "/api/v1/agents/" + agent.getId()
                + "/bundle?dlToken=" + bundleDownloadToken;

        log.info("Bundle issued — agent: {} ({}), org: {}, expires: {}",
                req.getAgentName(), agent.getId(), orgId, expiresAt);

        return new IssueBundleResult(agent.getId(), plainInstallKey, downloadUrl, expiresAt);
    }

    /**
     * Builds the agent install bundle ZIP and marks the download token as consumed.
     * The token is atomically consumed BEFORE the ZIP is built: a single UPDATE
     * sets bundle_downloaded_at only when it is currently NULL. If 0 rows are updated
     * the token has already been consumed (concurrent request or replay attack) and
     * a BundleExpiredException is thrown immediately, with no bundle data returned.
     * The returned bytes should be streamed directly to the HTTP response.
     */
    @Transactional
    public byte[] buildBundleZip(UUID agentId, String dlToken) throws Exception {
        // 1. Hash the token and look up the row
        String tokenHash = sha256Hex(dlToken);
        AgentInstallKey installKey = installKeyRepository.findByBundleDownloadTokenHash(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException("Bundle not found — token invalid"));

        // 2. Guard: expired (check before the atomic consume so the message is accurate)
        if (installKey.getExpiresAt().isBefore(Instant.now())) {
            throw new BundleExpiredException(
                    "Bundle download token has expired. Re-issue a new agent to get a fresh bundle.");
        }

        // 3. Verify the agentId in the path matches the row (prevents token probing across agents)
        if (!installKey.getAgent().getId().equals(agentId)) {
            throw new ResourceNotFoundException("Bundle not found — token invalid");
        }

        // 4. Atomically mark consumed BEFORE building the ZIP.
        //    Uses UPDATE ... WHERE bundle_downloaded_at IS NULL so only one concurrent
        //    caller wins; any other caller (including a replay) gets 0 rows updated.
        Instant downloadedAt = Instant.now();
        int updated = installKeyRepository.markDownloadedIfUnconsumed(installKey.getId(), downloadedAt);
        if (updated == 0) {
            throw new BundleExpiredException(
                    "Bundle has already been downloaded. Re-issue a new agent to get a fresh bundle.");
        }

        // 5. Build ZIP in memory (token is already consumed — safe to build now)
        byte[] zipBytes = buildZip(installKey, agentId);

        log.info("Bundle downloaded — agent: {}, org: {}", agentId, installKey.getOrgId());
        return zipBytes;
    }

    /**
     * Scheduled cleanup: delete expired, never-downloaded install key rows hourly.
     * Downloaded rows are retained for audit purposes.
     */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "AgentBundleService_cleanupExpiredInstallKeys",
                   lockAtMostFor = "PT45M", lockAtLeastFor = "PT30M")
    @Transactional
    public void cleanupExpiredInstallKeys() {
        installKeyRepository.deleteExpiredUndownloaded(Instant.now());
        log.debug("Expired agent install keys cleaned up");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private byte[] buildZip(AgentInstallKey installKey, UUID agentId) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {

            // certguard-agent.jar — sourced from classpath or GHCR
            addZipEntry(zos, "certguard-agent.jar", fetchAgentJarBytes());

            // bundle.cgb — the sealed payload
            addZipEntry(zos, "bundle.cgb", installKey.getSealedPayload());

            // application.properties — non-secret operational defaults only
            String appProps = """
                    # CertGuard Agent — operational defaults
                    # Secrets are stored in bundle.cgb and decrypted on first run.
                    agent.poll-interval-seconds=30
                    agent.scan-timeout-seconds=10
                    agent.scan-threads=4
                    """;
            addZipEntry(zos, "application.properties", appProps.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // run.sh
            String runSh = "#!/bin/bash\njava -jar certguard-agent.jar --bundle bundle.cgb\n";
            addZipEntry(zos, "run.sh", runSh.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // run.bat
            String runBat = "@echo off\njava -jar certguard-agent.jar --bundle bundle.cgb\n";
            addZipEntry(zos, "run.bat", runBat.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // README.txt
            String readme = buildReadme(agentId);
            addZipEntry(zos, "README.txt", readme.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }

    /**
     * Fetches the agent JAR bytes using a two-step strategy:
     * <ol>
     *   <li>Try {@code classpath:agent/certguard-agent.jar} — works in current production
     *       builds and in unit tests (via the fake JAR in {@code src/test/resources/agent/}).</li>
     *   <li>If the classpath resource is absent and {@code app.agent.artifact-url-template}
     *       is configured, fetch from that URL (typically a GHCR redirect) using the JDK
     *       built-in {@link HttpClient}. This is the post-PR-3 production path.</li>
     *   <li>Both absent → {@link IllegalStateException}.</li>
     * </ol>
     */
    private byte[] fetchAgentJarBytes() throws Exception {
        ClassPathResource classpathJar = new ClassPathResource("agent/certguard-agent.jar");
        if (classpathJar.exists()) {
            log.debug("Loading agent JAR from classpath resource");
            try (InputStream is = classpathJar.getInputStream()) {
                return is.readAllBytes();
            }
        }

        if (agentArtifactUrlTemplate != null && !agentArtifactUrlTemplate.isBlank()) {
            String url = String.format(agentArtifactUrlTemplate, appReleaseTag);
            log.info("Classpath agent JAR not found — fetching from GHCR: {}", url);
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException(
                        "Failed to fetch agent JAR from GHCR (HTTP " + status + "): " + url);
            }
            return response.body();
        }

        throw new IllegalStateException(
                "Agent JAR unavailable: no classpath resource and no artifact URL configured");
    }

    private void addZipEntry(ZipOutputStream zos, String name, InputStream data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        data.transferTo(zos);
        zos.closeEntry();
    }

    private void addZipEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private String buildReadme(UUID agentId) {
        return """
                CertGuard Agent — Installation Instructions
                ============================================

                This bundle contains:
                  certguard-agent.jar  — the CertGuard agent executable
                  bundle.cgb           — encrypted bootstrap configuration
                  application.properties — operational defaults (non-secret)
                  run.sh / run.bat     — convenience launch scripts

                Prerequisites
                -------------
                  - Java 17 or later must be on your PATH.
                  - Port 8443 outbound to your CertGuard server must be open.

                First-run Installation
                ----------------------
                1. Place all files in the same directory.
                2. On Linux/macOS, make run.sh executable:
                      chmod +x run.sh
                3. Run the agent. You will be prompted for the install key:

                   Linux/macOS:
                      ./run.sh

                   Windows:
                      run.bat

                   Or directly:
                      java -jar certguard-agent.jar --bundle bundle.cgb

                4. When prompted, enter the install key shown in the CertGuard portal.
                   You can also supply it non-interactively (avoid in production):
                      java -jar certguard-agent.jar --bundle bundle.cgb --install-key CGK-...
                   Or via environment variable:
                      export CERTGUARD_INSTALL_KEY=CGK-...
                      java -jar certguard-agent.jar --bundle bundle.cgb

                After First Run
                ---------------
                The agent decrypts bundle.cgb, registers with the server, and persists
                its credentials to application.properties. The install key and bundle.cgb
                are no longer needed after successful registration — you may delete them.

                Agent ID: %s

                For support, visit https://certguard.cloud/docs or contact support@certguard.cloud
                """.formatted(agentId);
    }

    private String generateBase32(int byteCount) {
        SecureRandom rng = new SecureRandom();
        byte[] raw = new byte[byteCount];
        rng.nextBytes(raw);
        // Base32-encode: each byte maps to ~1.6 chars; use simple bit extraction
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : raw) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
