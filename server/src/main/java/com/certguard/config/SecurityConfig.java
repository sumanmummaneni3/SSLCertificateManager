package com.certguard.config;

import com.certguard.security.AgentAuthFilter;
import com.certguard.security.JwtAuthenticationFilter;
import com.certguard.security.SalesAuthFilter;
import com.certguard.service.OAuth2UserService;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2UserService oAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    private final ApplicationContext applicationContext;
    private final SalesAuthFilter salesAuthFilter;

    @Value("${app.dev-mode:false}")
    private boolean devMode;

    @Value("${app.cors.allowed-origins:}")
    private String corsAllowedOriginsRaw;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          OAuth2UserService oAuth2UserService,
                          OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler,
                          ApplicationContext applicationContext,
                          SalesAuthFilter salesAuthFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.oAuth2UserService       = oAuth2UserService;
        this.oAuth2SuccessHandler    = oAuth2SuccessHandler;
        this.applicationContext      = applicationContext;
        this.salesAuthFilter         = salesAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        AgentAuthFilter agentAuthFilter = applicationContext.getBean(AgentAuthFilter.class);

        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth
                    .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.INCLUDE).permitAll()
                    // Agent
                    .requestMatchers("/api/v1/agent/register").permitAll()
                    .requestMatchers("/api/v1/agents/*/bundle").permitAll()
                    .requestMatchers("/agent/download").permitAll()
                    .requestMatchers("/agent/version").permitAll()
                    // Auth — always public
                    .requestMatchers("/api/v1/auth/config").permitAll()
                    .requestMatchers("/api/v1/auth/logout").permitAll()
                    .requestMatchers("/api/v1/auth/invite/**").permitAll();
                // Dev-only: token endpoint is only reachable in dev mode
                if (devMode) {
                    auth.requestMatchers("/api/v1/auth/dev-token").permitAll();
                }
                auth
                    // OAuth2
                    .requestMatchers("/oauth2/**").permitAll()
                    .requestMatchers("/login/oauth2/**").permitAll()
                    // Internal Sales API — authenticated via SalesAuthFilter
                    .requestMatchers("/api/internal/**").authenticated()
                    // Protected API — role checks via @PreAuthorize
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().permitAll();
            })
            .addFilterBefore(salesAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(agentAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        if (!devMode) {
            http.oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(ui -> ui.oidcUserService(oAuth2UserService))
                .successHandler(oAuth2SuccessHandler));
        } else {
            http.oauth2Login(oauth2 -> oauth2.disable());
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = parseOrigins(corsAllowedOriginsRaw);

        // Guard: refuse wildcard+credentials outside dev mode.
        boolean hasWildcard = origins.isEmpty() || origins.contains("*");
        if (!devMode && hasWildcard) {
            throw new IllegalStateException(
                "app.cors.allowed-origins must be a non-empty comma-separated list of explicit " +
                "origins (no wildcard) when app.dev-mode=false");
        }

        CorsConfiguration config = new CorsConfiguration();
        if (devMode && hasWildcard) {
            config.setAllowedOriginPatterns(List.of("*"));
        } else {
            config.setAllowedOrigins(origins);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
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
