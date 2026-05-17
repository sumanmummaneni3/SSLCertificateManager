package com.certguard.auth.controller;

import com.certguard.auth.dto.request.*;
import com.certguard.auth.dto.response.InitiateResponse;
import com.certguard.auth.dto.response.TokenResponse;
import com.certguard.auth.dto.response.ValidateResponse;
import com.certguard.auth.entity.User;
import com.certguard.auth.exception.AuthException;
import com.certguard.auth.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TokenService tokenService;
    private final GoogleAuthService googleAuthService;
    private final MicrosoftAuthService microsoftAuthService;
    private final EmailAuthService emailAuthService;
    private final RateLimitService rateLimitService;

    @Value("${auth.google.client-id:}")
    private String googleClientId;

    @Value("${auth.microsoft.client-id:}")
    private String microsoftClientId;

    /**
     * POST /api/auth/initiate
     * Returns the OAuth authorization URL (Google/Microsoft) or instructions for email flow.
     */
    @PostMapping("/initiate")
    public ResponseEntity<InitiateResponse> initiate(@Valid @RequestBody InitiateRequest req,
                                                      HttpServletRequest httpReq) {
        rateLimitService.check(clientIp(httpReq));

        InitiateResponse body = switch (req.provider()) {
            case "google" -> new InitiateResponse("google",
                    googleAuthService.buildAuthorizationUrl(req.redirectUri()), null);
            case "microsoft" -> new InitiateResponse("microsoft",
                    microsoftAuthService.buildAuthorizationUrl(req.redirectUri()), null);
            case "email" -> new InitiateResponse("email", null,
                    "POST credentials to /api/auth/token with provider=email");
            default -> throw new IllegalStateException("Unexpected provider: " + req.provider());
        };
        return ResponseEntity.ok(body);
    }

    /**
     * POST /api/auth/token
     * Exchange provider credentials/code/idToken for a unified JWT.
     */
    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest req,
                                                HttpServletRequest httpReq) {
        rateLimitService.check(clientIp(httpReq));
        TokenResponse resp = tokenService.exchange(req, clientIp(httpReq));
        return ResponseEntity.ok(resp);
    }

    /**
     * POST /api/auth/validate
     * Validates a unified JWT and returns its decoded claims.
     * Can be called by PHP or Java consumers to verify a token without sharing the secret.
     */
    @PostMapping("/validate")
    public ResponseEntity<ValidateResponse> validate(@Valid @RequestBody ValidateRequest req) {
        ValidateResponse resp = tokenService.validate(req.token());
        return ResponseEntity.ok(resp);
    }

    /**
     * POST /api/auth/register
     * Email-only registration. Sends verification email; returns 202 (no session token yet).
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody EmailRegisterRequest req,
                                                         HttpServletRequest httpReq) {
        rateLimitService.check(clientIp(httpReq));
        emailAuthService.register(req);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("message", "Verification email sent to " + req.email() + ". Please check your inbox."));
    }

    /**
     * GET /api/auth/verify-email?token=…
     * Verifies an email address using the token from the verification email.
     */
    @GetMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(@RequestParam String token) {
        emailAuthService.verifyEmail(token);
        return ResponseEntity.ok(Map.of("message", "Email verified successfully. You can now sign in."));
    }

    /**
     * POST /api/auth/resend-verification
     * Resends the verification email. Always returns 202 to avoid email enumeration.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest req,
            HttpServletRequest httpReq) {
        rateLimitService.check(clientIp(httpReq));
        emailAuthService.resendVerification(req.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("message", "If an unverified account exists for that email, a new verification link has been sent."));
    }

    /**
     * POST /api/auth/forgot-password
     * Sends a password-reset email. Always returns 202 to avoid email enumeration.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest req,
            HttpServletRequest httpReq) {
        rateLimitService.check(clientIp(httpReq));
        emailAuthService.forgotPassword(req.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("message", "If an account exists for that email, a password reset link has been sent."));
    }

    /**
     * POST /api/auth/reset-password
     * Resets the password using the token from the reset email.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        emailAuthService.resetPassword(req.token(), req.newPassword());
        return ResponseEntity.ok(Map.of("message", "Password reset successfully. You can now sign in with your new password."));
    }

    /**
     * DELETE /api/auth/session
     * Revokes the current session. The token is immediately invalid for /validate calls.
     */
    @DeleteMapping("/session")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authHeader) {
        String token = extractBearer(authHeader);
        tokenService.revokeSession(token);
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/auth/sessions
     * Revokes every active session for the authenticated user (logout from all devices).
     */
    @DeleteMapping("/sessions")
    public ResponseEntity<Void> logoutAll(@RequestHeader("Authorization") String authHeader) {
        String token = extractBearer(authHeader);
        tokenService.revokeAllSessions(token);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/auth/providers
     * Returns the list of enabled authentication providers. Public endpoint.
     */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> getProviders() {
        List<Map<String, Object>> providers = new ArrayList<>();

        if (googleClientId != null && !googleClientId.isBlank()) {
            providers.add(Map.of("id", "google", "type", "oauth2"));
        }
        if (microsoftClientId != null && !microsoftClientId.isBlank()) {
            providers.add(Map.of("id", "microsoft", "type", "oauth2"));
        }
        providers.add(Map.of("id", "email", "type", "password"));

        return ResponseEntity.ok(Map.of("providers", providers));
    }

    private String extractBearer(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new AuthException("Missing or malformed Authorization header");
        }
        return authHeader.substring(7);
    }

    private String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
    }
}
