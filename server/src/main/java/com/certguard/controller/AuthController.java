package com.certguard.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Always-active auth endpoints that must be reachable regardless of dev-mode.
 * The dev-token endpoint lives in DevAuthController and is gated by
 * {@code @ConditionalOnProperty(name = "app.dev-mode", havingValue = "true")}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Value("${app.dev-mode:false}")
    private boolean devMode;

    @GetMapping("/config")
    public ResponseEntity<?> authConfig() {
        return ResponseEntity.ok(Map.of("devMode", devMode));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }
}
