package com.certguard.controller;

import com.certguard.dto.request.SwitchOrgRequest;
import com.certguard.entity.User;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.UserRepository;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.security.JwtTokenProvider;
import com.certguard.service.ActiveOrgResolver;
import com.certguard.service.TokenRevocationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Always-active auth endpoints that must be reachable regardless of dev-mode.
 * The dev-token endpoint lives in DevAuthController and is gated by
 * {@code @ConditionalOnProperty(name = "app.dev-mode", havingValue = "true")}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final ActiveOrgResolver activeOrgResolver;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenRevocationService tokenRevocationService;

    @Value("${app.dev-mode:false}")
    private boolean devMode;

    public AuthController(UserRepository userRepository,
                          ActiveOrgResolver activeOrgResolver,
                          JwtTokenProvider jwtTokenProvider,
                          TokenRevocationService tokenRevocationService) {
        this.userRepository = userRepository;
        this.activeOrgResolver = activeOrgResolver;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenRevocationService = tokenRevocationService;
    }

    @GetMapping("/config")
    public ResponseEntity<?> authConfig() {
        return ResponseEntity.ok(Map.of("devMode", devMode));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    /**
     * RFC 0015 Phase 2 — LOCAL/DEV ONLY explicit org switch.
     *
     * <p><b>This endpoint mints an HS256 token, which the gateway does NOT accept</b>
     * (the gateway only validates RS256 tokens minted by certguard-auth-service — see
     * {@code JwtValidationFilter}). A token returned from here will be rejected by the
     * gateway on the very next request. It exists purely so the server can be exercised
     * without the gateway in front (local dev, bare-JWT fallback in
     * {@code JwtAuthenticationFilter}). The authoritative, production switch endpoint is
     * {@code POST /api/auth/switch-org} on certguard-auth-service, which owns the RS256
     * signing key.
     */
    @PostMapping("/switch-org")
    public ResponseEntity<?> switchOrg(@AuthenticationPrincipal CertGuardUserPrincipal principal,
                                       @Valid @RequestBody SwitchOrgRequest request) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        if (principal.isPlatformAdmin()) {
            throw new IllegalArgumentException(
                    "Platform admins switch via act-as-org, not switch-org");
        }

        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (tokenRevocationService.isRevoked(principal.getUserId(), request.orgId())) {
            throw new SecurityException("Access to that organization has been revoked");
        }

        ActiveOrgResolver.ActiveOrgContext ctx = activeOrgResolver.switchTo(user, request.orgId());

        String token = jwtTokenProvider.generateToken(
                principal.getUserId(), ctx.orgId(), principal.getEmail(), false, ctx.orgRole());

        log.warn("Server-minted HS256 switch-org token issued for user={} org={} — this token " +
                "is NOT valid behind the gateway (gateway only accepts RS256 tokens from " +
                "certguard-auth-service); local/dev use only.", principal.getUserId(), ctx.orgId());

        return ResponseEntity.ok(Map.of(
                "token", token,
                "orgId", ctx.orgId(),
                "orgRole", ctx.orgRole()
        ));
    }
}
