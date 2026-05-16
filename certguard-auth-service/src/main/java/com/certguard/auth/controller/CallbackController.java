package com.certguard.auth.controller;

import com.certguard.auth.dto.response.TokenResponse;
import com.certguard.auth.entity.User;
import com.certguard.auth.exception.AuthException;
import com.certguard.auth.service.GoogleAuthService;
import com.certguard.auth.service.MicrosoftAuthService;
import com.certguard.auth.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Handles the OAuth redirect-back from Google and Microsoft.
 *
 * Register these URIs in your OAuth app consoles:
 *   Google:    http://localhost:8090/api/auth/callback/google
 *   Microsoft: http://localhost:8090/api/auth/callback/microsoft
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/callback")
@RequiredArgsConstructor
public class CallbackController {

    private final GoogleAuthService googleAuthService;
    private final MicrosoftAuthService microsoftAuthService;
    private final TokenService tokenService;

    @Value("${auth.callback.base-url:http://localhost:8090}")
    private String baseUrl;

    /**
     * GET /api/auth/callback/google?code=xxx&state=yyy
     *
     * Google redirects here after the user authenticates.
     * Exchanges the code, creates a session, and redirects to the UI callback route.
     */
    @GetMapping("/google")
    public ResponseEntity<Void> googleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest req) {

        if (error != null) {
            log.warn("Google OAuth error: {}", error);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, buildErrorRedirect("Google login failed: " + error))
                    .build();
        }

        String redirectUri = baseUrl + "/api/auth/callback/google";
        User user = googleAuthService.authenticateWithCode(code, redirectUri);
        TokenResponse token = tokenService.createSession(user, "google", clientIp(req));

        log.info("Google OAuth callback success for {}", user.getEmail());
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, buildCallbackRedirect(token))
                .build();
    }

    /**
     * GET /api/auth/callback/microsoft?code=xxx
     *
     * Microsoft redirects here after the user authenticates.
     * Exchanges the code, creates a session, and redirects to the UI callback route.
     */
    @GetMapping("/microsoft")
    public ResponseEntity<Void> microsoftCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription,
            HttpServletRequest req) {

        if (error != null) {
            log.warn("Microsoft OAuth error: {} — {}", error, errorDescription);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, buildErrorRedirect("Microsoft login failed: " + errorDescription))
                    .build();
        }
        if (code == null) {
            throw new AuthException("No authorization code received from Microsoft");
        }

        String redirectUri = baseUrl + "/api/auth/callback/microsoft";
        User user = microsoftAuthService.authenticateWithCode(code, redirectUri);
        TokenResponse token = tokenService.createSession(user, "microsoft", clientIp(req));

        log.info("Microsoft OAuth callback success for {}", user.getEmail());
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, buildCallbackRedirect(token))
                .build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
    }

    private String buildCallbackRedirect(TokenResponse t) {
        return baseUrl + "/auth/callback#token=" + t.token()
                + "&expiresIn=" + t.expiresIn()
                + "&email=" + URLEncoder.encode(t.email() != null ? t.email() : "", StandardCharsets.UTF_8);
    }

    private String buildErrorRedirect(String message) {
        return baseUrl + "/auth/callback#error=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }
}
