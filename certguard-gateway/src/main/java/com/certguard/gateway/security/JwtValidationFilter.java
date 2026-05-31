package com.certguard.gateway.security;

import com.certguard.gateway.config.GatewayJwtProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reactive global filter that validates RS256 JWTs on every inbound request.
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

    private static final String BEARER_PREFIX = "Bearer ";

    /** Paths that bypass JWT validation entirely. */
    private static final List<String> PUBLIC_PATTERNS = List.of(
            "/api/auth/**",
            // Only the specific server auth endpoints that are genuinely unauthenticated.
            // /api/v1/auth/me is intentionally excluded so the gateway injects X-CG-* headers.
            "/api/v1/auth/config",
            "/api/v1/auth/logout",
            "/api/v1/auth/dev-token",
            "/api/v1/auth/invite/**",
            // One-time agent installer bundle download — authenticated by the dlToken
            // query param (not a session JWT). Browser download navigations cannot send
            // the SPA's Authorization header, so this must bypass gateway JWT validation;
            // the server validates/consumes the dlToken (410 Gone if expired/used).
            "/api/v1/agents/*/bundle",
            "/actuator/**",
            "/oauth2/**",
            "/login/**",
            // React SPA static assets — served by the app, no auth required
            "/",
            "/index.html",
            "/auth/**",
            "/assets/**",
            "/favicon.ico",
            "/*.js",
            "/*.css",
            "/*.svg",
            "/*.png",
            "/*.ico",
            "/*.map"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final GatewayJwtProperties gwJwtProps;

    private volatile RSAPublicKey publicKey;

    public JwtValidationFilter(GatewayJwtProperties gwJwtProps) {
        this.gwJwtProps = gwJwtProps;
    }

    @PostConstruct
    public void initKeys() {
        int maxAttempts = 10;
        int delaySeconds = 6;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                this.publicKey = fetchPublicKey();
                log.info("RS256 public key loaded from JWKS at {} (attempt {})",
                        gwJwtProps.jwksUri(), attempt);
                return;
            } catch (Exception ex) {
                if (attempt == maxAttempts) {
                    // Auth-service is not yet reachable — allow the gateway to start anyway.
                    // Public routes (/api/auth/**, /actuator/**, static assets) remain fully
                    // available. Protected routes return 503 until the key is loaded lazily
                    // on the first successful request after auth-service comes up.
                    log.warn("JWKS unavailable after {} attempts — gateway starting without RS256 key. " +
                             "Protected routes will return 503 until auth-service is reachable. Cause: {}",
                             maxAttempts, ex.getMessage());
                    return;
                }
                log.warn("JWKS fetch attempt {}/{} failed ({}), retrying in {}s…",
                        attempt, maxAttempts, ex.getMessage(), delaySeconds);
                try { Thread.sleep(delaySeconds * 1000L); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for JWKS — gateway starting without RS256 key");
                    return;
                }
            }
        }
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

        // Lazy key load: if auth-service wasn't reachable at startup, try once now.
        if (publicKey == null) {
            try {
                refreshPublicKey();
            } catch (Exception ex) {
                log.warn("Auth-service JWKS still unavailable on request to {}: {}", path, ex.getMessage());
                return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "Authentication service is temporarily unavailable — please retry in a moment",
                        "https://certguard.dev/problems/auth-service-unavailable");
            }
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
            log.debug("JWT validation failed for path {}: {} — attempting key refresh", path, ex.getMessage());
            String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
            if (msg.contains("unable to find") || msg.contains("signature")) {
                try {
                    refreshPublicKey();
                    claims = parseToken(token);
                } catch (ExpiredJwtException retryEx) {
                    log.debug("Rejected expired JWT after key refresh for path {}: {}", path, retryEx.getMessage());
                    return reject(exchange, HttpStatus.UNAUTHORIZED, "JWT token has expired",
                            "https://certguard.dev/problems/token-expired");
                } catch (JwtException | IllegalArgumentException retryEx) {
                    log.debug("Rejected invalid JWT after key refresh for path {}: {}", path, retryEx.getMessage());
                    return reject(exchange, HttpStatus.UNAUTHORIZED, "JWT token is invalid",
                            "https://certguard.dev/problems/invalid-token");
                }
            } else {
                return reject(exchange, HttpStatus.UNAUTHORIZED, "JWT token is invalid",
                        "https://certguard.dev/problems/invalid-token");
            }
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
                .verifyWith(publicKey)
                .requireIssuer(gwJwtProps.issuer())
                .requireAudience(gwJwtProps.audience())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private void refreshPublicKey() {
        log.info("Refreshing RS256 public key from JWKS at {}", gwJwtProps.jwksUri());
        this.publicKey = fetchPublicKey();
        log.info("RS256 public key refreshed successfully");
    }

    private RSAPublicKey fetchPublicKey() {
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gwJwtProps.jwksUri()))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "JWKS endpoint returned HTTP " + response.statusCode() +
                        " from " + gwJwtProps.jwksUri());
            }

            JWKSet jwkSet = JWKSet.parse(response.body());
            RSAKey rsaKey = jwkSet.getKeys().stream()
                    .filter(k -> k instanceof RSAKey)
                    .map(k -> (RSAKey) k)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No RSA key found in JWKS at " + gwJwtProps.jwksUri()));

            return rsaKey.toRSAPublicKey();

        } catch (IllegalStateException ex) {
            log.error("Failed to load RS256 public key from JWKS: {}", ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to load RS256 public key from JWKS at {}: {}", gwJwtProps.jwksUri(), ex.getMessage());
            throw new IllegalStateException(
                    "Cannot load RS256 public key from JWKS at " + gwJwtProps.jwksUri(), ex);
        }
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

        String userId        = claims.getSubject();                              // sub = user UUID
        String orgId         = claims.get("orgId",         String.class);      // nullable
        String role          = claims.get("orgRole",        String.class);      // nullable
        String email         = claims.get("email",          String.class);      // nullable
        Boolean platformAdmin = claims.get("platformAdmin", Boolean.class);     // nullable

        final String finalRequestId = requestId;

        return original.mutate()
                .headers(headers -> {
                    // 1. Strip all inbound X-CG-* headers to prevent forgery.
                    //    Collect the keys first to avoid ConcurrentModificationException.
                    java.util.List<String> cgHeaders = headers.headerNames().stream()
                            .filter(name -> name.toLowerCase().startsWith("x-cg-"))
                            .toList();
                    cgHeaders.forEach(headers::remove);

                    // 2. Inject trusted values from validated JWT claims.
                    if (userId != null)        { headers.set("X-CG-User-Id",        userId); }
                    if (orgId != null)         { headers.set("X-CG-Org-Id",         orgId); }
                    if (role != null)          { headers.set("X-CG-Role",           role); }
                    if (email != null)         { headers.set("X-CG-Email",          email); }
                    if (platformAdmin != null) { headers.set("X-CG-Platform-Admin", platformAdmin.toString()); }
                    headers.set("X-Request-Id", finalRequestId);

                    // 3. Re-add the original Authorization header.
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
