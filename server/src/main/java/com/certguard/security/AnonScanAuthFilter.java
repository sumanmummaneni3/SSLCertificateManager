package com.certguard.security;

import com.certguard.entity.AnonScanSession;
import com.certguard.enums.AnonSessionStatus;
import com.certguard.repository.AnonScanSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;

/**
 * Authentication filter for anonymous scan write paths (RFC 0011 Part B).
 *
 * Applies only to the agent-facing write paths that require a valid scanToken:
 *   POST /api/v1/anon/discovery-results
 *   GET  /api/v1/anon/jobs
 *
 * The scanToken is read from the X-Anon-Scan-Token header, SHA-256-hashed,
 * and resolved against anon_scan_sessions. The session is stored as a request
 * attribute ("anonSession") for controllers to read.
 *
 * No Spring Security principal is set — these paths are outside the JWT flow.
 * Modelled on AgentAuthFilter.
 */
@Slf4j
@RequiredArgsConstructor
public class AnonScanAuthFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Anon-Scan-Token";

    /** Write paths that require a valid scanToken. */
    private static final Set<String> GUARDED_PREFIXES = Set.of(
            "/api/v1/anon/discovery-results",
            "/api/v1/anon/jobs"
    );

    private final AnonScanSessionRepository sessionRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return GUARDED_PREFIXES.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String rawToken = request.getHeader(TOKEN_HEADER);
        if (rawToken == null || rawToken.isBlank()) {
            sendUnauthorized(request, response, "Missing " + TOKEN_HEADER + " header");
            return;
        }

        String hash = sha256Hex(rawToken);
        AnonScanSession session = sessionRepository.findByScanTokenHash(hash).orElse(null);

        if (session == null) {
            log.warn("Anon scan token not found — hash prefix: {}...",
                    hash.substring(0, Math.min(8, hash.length())));
            sendUnauthorized(request, response, "Invalid scan token");
            return;
        }

        if (session.getStatus() != AnonSessionStatus.ACTIVE) {
            sendUnauthorized(request, response,
                    "Scan session is no longer active: " + session.getStatus());
            return;
        }

        if (Instant.now().isAfter(session.getScanExpiresAt())) {
            sendUnauthorized(request, response, "Scan token has expired");
            return;
        }

        request.setAttribute("anonSession", session);
        chain.doFilter(request, response);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void sendUnauthorized(HttpServletRequest request,
                                   HttpServletResponse response,
                                   String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        String body = String.format(
                "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"%s\",\"instance\":\"%s\"}",
                escapeJson(detail),
                escapeJson(request.getRequestURI()));
        response.getWriter().write(body);
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
