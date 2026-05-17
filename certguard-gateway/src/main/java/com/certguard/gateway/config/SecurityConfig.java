package com.certguard.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reactive security configuration for the API Gateway.
 *
 * <p>Uses {@link ServerHttpSecurity} (NOT the servlet {@code HttpSecurity}) because
 * Spring Cloud Gateway runs on WebFlux. Mixing the two will cause a startup failure.
 *
 * <p>CORS policy mirrors the wildcard-credentials guard pattern from
 * {@code certguard-server/SecurityConfig.java} (lines ~106-130): wildcard origins are
 * permitted only in dev mode; production requires an explicit allow-list.
 */
@Slf4j
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${gateway.cors.allowed-origins:}")
    private String corsAllowedOriginsRaw;

    @Value("${gateway.dev-mode:false}")
    private boolean devMode;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            // Stateless API — no server-side session.
            .securityContextRepository(org.springframework.security.web.server.context.NoOpServerSecurityContextRepository.getInstance())
            // CSRF disabled: stateless JWT-based API; no cookie-based CSRF surface on the gateway itself.
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeExchange(exchanges -> exchanges
                // Auth-service endpoints and actuator health are public.
                .pathMatchers("/api/auth/**").permitAll()
                .pathMatchers("/actuator/health").permitAll()
                // JWT validation is handled by JwtValidationFilter (WebFilter, higher priority).
                .anyExchange().permitAll()
            )
            // Disable form login and HTTP Basic — pure JWT bearer.
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = parseOrigins(corsAllowedOriginsRaw);

        // Guard: refuse wildcard + credentials outside dev mode.
        // This mirrors the pattern in certguard-server SecurityConfig lines ~110-115.
        boolean hasWildcard = origins.isEmpty() || origins.contains("*");
        if (!devMode && hasWildcard) {
            throw new IllegalStateException(
                    "gateway.cors.allowed-origins must be a non-empty comma-separated list of explicit " +
                    "origins (no wildcard) when gateway.dev-mode=false");
        }

        CorsConfiguration config = new CorsConfiguration();
        // setAllowedOriginPatterns is required in Spring 6.x when allowCredentials=true;
        // setAllowedOrigins causes CORS rejection even for matching origins in this mode.
        if (devMode && hasWildcard) {
            config.setAllowedOriginPatterns(List.of("*"));
        } else {
            config.setAllowedOriginPatterns(origins);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Forwarded-For"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
