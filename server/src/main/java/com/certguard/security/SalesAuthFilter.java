package com.certguard.security;

import com.certguard.entity.SalesApiKey;
import com.certguard.enums.SalesKeyStatus;
import com.certguard.repository.SalesApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Authentication filter for the internal Sales API (/api/internal/v1/sales/**).
 * Validates X-Sales-Key-Id (UUID) and X-Sales-Key (plain key) headers against
 * the sales_api_keys table. On success, sets ROLE_SALES_APP in SecurityContext
 * and stashes the key label as the "salesKeyLabel" request attribute.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesAuthFilter extends OncePerRequestFilter {

    private static final String SALES_KEY_ID_HEADER = "X-Sales-Key-Id";
    private static final String SALES_KEY_HEADER    = "X-Sales-Key";
    private static final String SALES_KEY_LABEL_ATTR = "salesKeyLabel";

    private static final String INTERNAL_SALES_PATH    = "/api/internal/v1/sales/";
    private static final String INTERNAL_SALES_PING    = "/api/internal/v1/sales/ping";

    private final SalesApiKeyRepository salesApiKeyRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Only apply this filter to the internal sales API paths
        return !path.startsWith(INTERNAL_SALES_PATH) && !path.equals(INTERNAL_SALES_PING);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String keyIdStr  = request.getHeader(SALES_KEY_ID_HEADER);
        String plainKey  = request.getHeader(SALES_KEY_HEADER);

        // Missing headers — reject immediately with 401
        if (keyIdStr == null || keyIdStr.isBlank() || plainKey == null || plainKey.isBlank()) {
            sendUnauthorized(request, response, "Missing sales API credentials");
            return;
        }

        UUID keyId;
        try {
            keyId = UUID.fromString(keyIdStr);
        } catch (IllegalArgumentException e) {
            sendUnauthorized(request, response, "Invalid sales key ID format");
            return;
        }

        SalesApiKey apiKey = salesApiKeyRepository.findById(keyId).orElse(null);
        if (apiKey == null) {
            sendUnauthorized(request, response, "Sales API key not found");
            return;
        }

        if (apiKey.getStatus() != SalesKeyStatus.ACTIVE) {
            sendUnauthorized(request, response, "Sales API key is not active: " + apiKey.getStatus());
            return;
        }

        if (apiKey.getExpiresAt() != null && !Instant.now().isBefore(apiKey.getExpiresAt())) {
            sendUnauthorized(request, response, "Sales API key has expired");
            return;
        }

        if (!passwordEncoder.matches(plainKey, apiKey.getKeyHash())) {
            log.warn("Invalid sales API key supplied for key id: {}", keyId);
            sendUnauthorized(request, response, "Invalid sales API credentials");
            return;
        }

        // Update lastUsedAt synchronously (best-effort — failure should not block the request)
        try {
            apiKey.setLastUsedAt(Instant.now());
            salesApiKeyRepository.save(apiKey);
        } catch (Exception e) {
            log.warn("Failed to update sales API key last_used_at: {}", e.getMessage());
        }

        // Stash label for downstream audit use
        request.setAttribute(SALES_KEY_LABEL_ATTR, apiKey.getLabel());

        // Grant ROLE_SALES_APP authority
        var auth = new UsernamePasswordAuthenticationToken(
                apiKey.getId().toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_SALES_APP")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletRequest request,
                                   HttpServletResponse response,
                                   String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        String body = String.format(
                "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"%s\",\"instance\":\"%s\"}",
                escapeJson(message),
                escapeJson(request.getRequestURI()));
        response.getWriter().write(body);
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
