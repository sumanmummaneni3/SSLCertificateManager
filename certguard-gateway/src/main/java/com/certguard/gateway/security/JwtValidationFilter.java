package com.certguard.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reactive global filter that validates HS256 JWTs on every inbound request.
 *
 * <p>Paths exempt from JWT validation:
 * <ul>
 *   <li>/api/auth/** — auth-service endpoints (login, token exchange, etc.)</li>
 *   <li>/actuator/** — health and info probes</li>
 * </ul>
 *
 * <p>On valid token:
 * <ol>
 *   <li>Strips all inbound X-CG-* headers (prevent client forgery)</li>
 *   <li>Injects X-CG-User-Id, X-CG-Org-Id, X-CG-Role from JWT claims</li>
 *   <li>Injects X-Request-Id (generates UUID if absent)</li>
 *   <li>Preserves the original Authorization header downstream</li>
 * </ol>
 *
 * <p>On invalid or missing token: returns HTTP 401 with RFC 9457 ProblemDetail JSON.
 */
@Slf4j
@Component
public class JwtValidationFilter implements WebFilter, Ordered {

    /**
     * High priority: run before all route-level filters but after Spring Security's
     * reactive filter chain. Use Ordered.HIGHEST_PRECEDENCE + 10 so there is still
     * room for infrastructure filters to run first if ever needed.
     */
    private static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final String ISSUER   = "certguard-cloud";
    private static final String AUDIENCE = "certguard-ui";

    private static final String BEARER_PREFIX = "Bearer ";

    /** Paths that bypass JWT validation entirely. */
    private static final List<String> PUBLIC_PATTERNS = List.of(
            "/api/auth/**",
            "/actuator/**",
            "/oauth2/**",
            "/login/**"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Value("${gateway.jwt.secret}")
    private String jwtSecret;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        if (jwtSecret == null || jwtSecret.length() < 64) {
            throw new IllegalStateException(
                    "gateway.jwt.secret must be at least 64 characters (same value as AUTH_JWT_SECRET)");
        }
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Missing or malformed Authorization header",
                    "https://certguard.dev/problems/missing-token");
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        Claims claims;
        try {
            claims = parseToken(token);
        } catch (ExpiredJwtException ex) {
            log.debug("Rejected expired JWT for path {}: {}", path, ex.getMessage());
            return reject(exchange, HttpStatus.UNAUTHORIZED, "JWT token has expired",
                    "https://certguard.dev/problems/token-expired");
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected invalid JWT for path {}: {}", path, ex.getMessage());
            return reject(exchange, HttpStatus.UNAUTHORIZED, "JWT token is invalid",
                    "https://certguard.dev/problems/invalid-token");
        }

        // Build the mutated request: strip inbound X-CG-* headers, inject trusted values.
        ServerHttpRequest mutatedRequest = buildMutatedRequest(exchange.getRequest(), claims, authHeader);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private boolean isPublicPath(String path) {
        return PUBLIC_PATTERNS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Strips all X-CG-* headers from the inbound request (defence-in-depth against
     * client forgery) then injects trusted values derived from validated JWT claims.
     * The original Authorization header is preserved unchanged.
     */
    private ServerHttpRequest buildMutatedRequest(ServerHttpRequest original,
                                                  Claims claims,
                                                  String originalAuthHeader) {
        // Determine X-Request-Id: reuse if present (tracing), otherwise generate.
        String requestId = original.getHeaders().getFirst("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        String userId = claims.getSubject();                             // sub = user UUID
        String orgId  = claims.get("orgId",  String.class);             // nullable
        String role   = claims.get("orgRole", String.class);            // nullable

        final String finalRequestId = requestId;

        return original.mutate()
                .headers(headers -> {
                    // 1. Strip all inbound X-CG-* headers to prevent forgery.
                    //    Collect the keys first to avoid ConcurrentModificationException.
                    java.util.List<String> cgHeaders = headers.headerNames().stream()
                            .filter(name -> name.toLowerCase().startsWith("x-cg-"))
                            .toList();
                    cgHeaders.forEach(headers::remove);

                    // 2. Inject trusted values.
                    if (userId != null) {
                        headers.set("X-CG-User-Id", userId);
                    }
                    if (orgId != null) {
                        headers.set("X-CG-Org-Id", orgId);
                    }
                    if (role != null) {
                        headers.set("X-CG-Role", role);
                    }
                    headers.set("X-Request-Id", finalRequestId);

                    // 3. Re-add the original Authorization header (was cleared by removeIf
                    //    only if it started with "x-cg-", so it is still present here —
                    //    but be explicit to guarantee downstream sees it).
                    headers.set(HttpHeaders.AUTHORIZATION, originalAuthHeader);
                })
                .build();
    }

    private Mono<Void> reject(ServerWebExchange exchange,
                              HttpStatus status,
                              String detail,
                              String typeUri) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String json = String.format(
                "{\"status\":%d,\"type\":\"%s\",\"title\":\"%s\",\"detail\":\"%s\"," +
                "\"instance\":\"%s\",\"timestamp\":\"%s\"}",
                status.value(), typeUri, status.getReasonPhrase(), detail,
                exchange.getRequest().getPath().value(), Instant.now().toString());

        DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
