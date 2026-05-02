package com.certguard.security;

import com.certguard.entity.Agent;
import com.certguard.enums.AgentStatus;
import com.certguard.repository.AgentRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class AgentAuthFilter extends OncePerRequestFilter {

    private static final String AGENT_KEY_HEADER = "X-Agent-Key";
    private static final String AGENT_ID_HEADER  = "X-Agent-Id";

    // These paths are handled by JWT auth or are public — skip agent filter
    private static final List<String> SKIP_PATHS = List.of(
            "/api/v1/agent/ca-cert",
            "/api/v1/agent/register",
            "/api/v1/agent/tokens",
            "/api/v1/agent/list"
    );

    private final AgentRepository agentRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip entirely if not an agent path
        if (!path.startsWith("/api/v1/agent/")) return true;
        // Skip admin and public endpoints — handled by JWT filter
        if (SKIP_PATHS.contains(path)) return true;
        // Skip revoke endpoint (admin JWT endpoint)
        if (path.matches("/api/v1/agent/[^/]+/revoke")) return true;
        return false;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String agentKey   = request.getHeader(AGENT_KEY_HEADER);
        String agentIdStr = request.getHeader(AGENT_ID_HEADER);

        if (agentKey == null || agentKey.isBlank()
                || agentIdStr == null || agentIdStr.isBlank()) {
            sendUnauthorized(request, response, "Missing agent credentials");
            return;
        }

        UUID agentId;
        try {
            agentId = UUID.fromString(agentIdStr);
        } catch (IllegalArgumentException e) {
            sendUnauthorized(request, response, "Invalid agent ID format");
            return;
        }

        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            sendUnauthorized(request, response, "Agent not found");
            return;
        }

        if (agent.getStatus() != AgentStatus.ACTIVE) {
            sendUnauthorized(request, response, "Agent is not active: " + agent.getStatus());
            return;
        }

        if (!passwordEncoder.matches(agentKey, agent.getAgentKeyHash())) {
            log.warn("Invalid agent key for agent: {}", agentId);
            sendUnauthorized(request, response, "Invalid agent credentials");
            return;
        }

        // Update last seen
        try {
            agent.setLastSeenAt(Instant.now());
            agentRepository.save(agent);
        } catch (Exception e) {
            log.warn("Failed to update agent last_seen_at: {}", e.getMessage());
        }

        request.setAttribute("authenticatedAgent", agent);
        request.setAttribute("authenticatedAgentKey", agentKey);

        // Mark request as authenticated for Spring Security
        var auth = new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(
                agent.getId().toString(), null, List.of());
        org.springframework.security.core.context.SecurityContextHolder
                .getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletRequest request,
                                   HttpServletResponse response,
                                   String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        // RFC 9457 ProblemDetail — consistent with GlobalExceptionHandler
        String body = String.format(
                "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"%s\",\"instance\":\"%s\"}",
                escapeJson(message),
                escapeJson(request.getRequestURI()));
        response.getWriter().write(body);
    }

    /** Escapes characters that would break the inline JSON string. */
    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
