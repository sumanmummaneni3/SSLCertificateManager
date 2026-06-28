package com.certguard.controller;

import com.certguard.dto.request.AnonDiscoveryResultsRequest;
import com.certguard.dto.response.AnonSessionCreateResponse;
import com.certguard.dto.response.AnonSessionDashboardResponse;
import com.certguard.entity.AnonScanSession;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.service.AnonScanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * REST endpoints for the anonymous free-tier scan (RFC 0011 Part B).
 *
 * Public paths (no auth):
 *   POST   /api/v1/anon/sessions               — create session, return tokens
 *   GET    /api/v1/anon/download               — serve stamped agent config ZIP
 *   GET    /api/v1/anon/sessions/{viewToken}   — read-only dashboard
 *   DELETE /api/v1/anon/sessions/{viewToken}   — GDPR erasure
 *
 * AnonScanAuthFilter-guarded paths (X-Anon-Scan-Token required):
 *   GET    /api/v1/anon/jobs                   — poll for pending DISCOVERY job
 *   POST   /api/v1/anon/discovery-results      — push results from agent
 *
 * JWT-authenticated path:
 *   POST   /api/v1/anon/sessions/{viewToken}/claim — claim into org after signup
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/anon")
@RequiredArgsConstructor
public class AnonScanController {

    private final AnonScanService anonScanService;

    @Value("${app.server.base-url:http://localhost:8080}")
    private String serverBaseUrl;

    // ── Session lifecycle ─────────────────────────────────────────────────────

    /**
     * POST /api/v1/anon/sessions
     * Creates a new anonymous session. Rate-limited by X-Forwarded-For IP.
     * The IP is checked for rate-limiting but is NOT stored.
     */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public AnonSessionCreateResponse createSession(HttpServletRequest request) {
        String clientIp = resolveClientIp(request);
        return anonScanService.createSession(serverBaseUrl, clientIp);
    }

    /**
     * GET /api/v1/anon/download?token={scanToken}
     * Returns a personalised agent bundle ZIP containing:
     *   - certguard-agent.jar  (pre-built agent binary from classpath)
     *   - application.properties  (stamped with scanToken, server URL, ANONYMOUS mode)
     *   - README.txt  (run instructions)
     *
     * The scanToken is validated against the DB before serving. It is NOT consumed
     * here — the agent still needs it to submit discovery results.
     * Returns 404 if the token is unknown or the session has been deleted.
     */
    @GetMapping(value = "/download", produces = "application/zip")
    public ResponseEntity<byte[]> downloadBundle(@RequestParam("token") String token) throws Exception {
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Validate that a live session exists for this scan token
        boolean sessionExists = anonScanService.findSessionByScanToken(token).isPresent();
        if (!sessionExists) {
            log.warn("Download requested for unknown/deleted anon scan token (prefix: {}...)",
                    token.length() >= 8 ? token.substring(0, 8) : token);
            return ResponseEntity.notFound().build();
        }

        String props = "certguard.server.url=" + serverBaseUrl + "\n"
                + "certguard.agent.mode=ANONYMOUS\n"
                + "certguard.agent.anon.scan-token=" + token + "\n"
                + "certguard.agent.scan-timeout-seconds=5\n"
                + "certguard.agent.poll-interval-seconds=10\n";

        String readme = "CertGuard Anonymous Network Scan\n"
                + "=================================\n"
                + "1. Ensure Java 17+ is installed: java -version\n"
                + "2. Run the scanner:\n"
                + "   java -jar certguard-agent.jar\n"
                + "\n"
                + "The agent will scan your local network and post results to CertGuard.\n"
                + "Results are available at: " + serverBaseUrl + "/scan/<viewToken>\n"
                + "Results are automatically deleted after 7 days.\n"
                + "No IP addresses are stored outside your network.\n";

        byte[] zipBytes = buildAgentBundle(props, readme);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"certguard-scanner.zip\"")
                .body(zipBytes);
    }

    // ── Agent-facing endpoints (guarded by AnonScanAuthFilter) ───────────────

    /**
     * GET /api/v1/anon/jobs
     * Returns a pending DISCOVERY job while session is ACTIVE.
     * Returns empty list once SCAN_COMPLETE (one-scan rule).
     * Requires X-Anon-Scan-Token header (validated by AnonScanAuthFilter).
     */
    @GetMapping("/jobs")
    public List<Object> getPendingJobs(HttpServletRequest request) {
        AnonScanSession session = (AnonScanSession) request.getAttribute("anonSession");
        return anonScanService.getPendingJobs(session);
    }

    /**
     * POST /api/v1/anon/discovery-results
     * Agent pushes NIC subnets and discovered devices.
     * Requires X-Anon-Scan-Token header (validated by AnonScanAuthFilter).
     * Returns 409 if already submitted (one-scan rule).
     */
    @PostMapping("/discovery-results")
    @ResponseStatus(HttpStatus.OK)
    public void submitDiscoveryResults(
            @RequestBody @Valid AnonDiscoveryResultsRequest req,
            HttpServletRequest request) {
        AnonScanSession session = (AnonScanSession) request.getAttribute("anonSession");
        anonScanService.ingestDiscoveryResults(session, req);
    }

    // ── Public read & management ──────────────────────────────────────────────

    /**
     * GET /api/v1/anon/sessions/{viewToken}
     * Returns the read-only dashboard data for this anonymous session.
     * No authentication required — viewToken is the proof of access.
     */
    @GetMapping("/sessions/{viewToken}")
    public AnonSessionDashboardResponse getDashboard(@PathVariable String viewToken) {
        return anonScanService.getSessionForView(viewToken);
    }

    /**
     * POST /api/v1/anon/sessions/{viewToken}/claim
     * Claims a completed anonymous session into the authenticated user's org.
     * Requires valid JWT. Returns the subnet CIDRs for pre-populating network scan.
     */
    @PostMapping("/sessions/{viewToken}/claim")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> claimSession(
            @PathVariable String viewToken,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) {
        List<String> subnetCidrs = anonScanService.claimSession(
                viewToken, principal.getOrgId(), principal.getUserId());
        return Map.of(
                "claimed", true,
                "subnetCidrs", subnetCidrs,
                "message", "Scan claimed. Use these CIDRs to launch a full network scan."
        );
    }

    /**
     * DELETE /api/v1/anon/sessions/{viewToken}
     * GDPR erasure — no auth required; viewToken is the proof of ownership.
     * Soft-deletes the session; scheduler hard-deletes it.
     */
    @DeleteMapping("/sessions/{viewToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable String viewToken) {
        anonScanService.deleteSession(viewToken);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Resolves client IP from X-Forwarded-For or remote address. Does NOT store the result. */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Assembles the agent ZIP bundle in memory.
     * Three entries:
     *   1. certguard-agent.jar    — loaded from classpath resource
     *   2. application.properties — generated, stamped with session token
     *   3. README.txt             — human-readable run instructions
     */
    private byte[] buildAgentBundle(String propsContent, String readmeContent) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {

            // 1. Agent JAR
            ClassPathResource agentJar = new ClassPathResource("agent/certguard-agent.jar");
            if (!agentJar.exists()) {
                throw new IllegalStateException(
                    "agent/certguard-agent.jar not found on classpath — ensure it is committed to " +
                    "server/src/main/resources/agent/ and not excluded by .gitignore");
            }
            zip.putNextEntry(new ZipEntry("certguard-agent.jar"));
            try (InputStream in = agentJar.getInputStream()) {
                in.transferTo(zip);
            }
            zip.closeEntry();

            // 2. application.properties
            zip.putNextEntry(new ZipEntry("application.properties"));
            zip.write(propsContent.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            // 3. README.txt
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(readmeContent.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return baos.toByteArray();
    }
}
