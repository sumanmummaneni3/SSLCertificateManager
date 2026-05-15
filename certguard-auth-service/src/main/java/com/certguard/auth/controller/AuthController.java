package com.certguard.auth.controller;

import com.certguard.auth.dto.request.EmailRegisterRequest;
import com.certguard.auth.dto.request.InitiateRequest;
import com.certguard.auth.dto.request.TokenRequest;
import com.certguard.auth.dto.request.ValidateRequest;
import com.certguard.auth.dto.response.InitiateResponse;
import com.certguard.auth.dto.response.TokenResponse;
import com.certguard.auth.dto.response.ValidateResponse;
import com.certguard.auth.entity.User;
import com.certguard.auth.exception.AuthException;
import com.certguard.auth.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TokenService tokenService;
    private final GoogleAuthService googleAuthService;
    private final MicrosoftAuthService microsoftAuthService;
    private final EmailAuthService emailAuthService;
    private final RateLimitService rateLimitService;

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
     * Email-only registration. Returns a session token immediately (email unverified).
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody EmailRegisterRequest req,
                                                   HttpServletRequest httpReq) {
        rateLimitService.check(clientIp(httpReq));
        User user = emailAuthService.register(req);
        TokenResponse resp = tokenService.createSession(user, "email", clientIp(httpReq));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
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
