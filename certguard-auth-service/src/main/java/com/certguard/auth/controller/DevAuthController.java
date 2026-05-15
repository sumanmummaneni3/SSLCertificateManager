package com.certguard.auth.controller;

import com.certguard.auth.entity.User;
import com.certguard.auth.repository.UserRepository;
import com.certguard.auth.service.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Issues tokens without going through Google/Microsoft — local development only.
 * Only active when auth.dev-mode=true. Never deploy with this flag enabled in production.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "auth.dev-mode", havingValue = "true")
public class DevAuthController {

    private final UserRepository userRepository;
    private final TokenService tokenService;

    /**
     * POST /api/auth/dev-token?email=alice@example.com&provider=google&name=Alice
     *
     * Creates the user if they don't exist, then issues a real JWT exactly as the
     * normal OAuth flow would — skipping the actual Google/Microsoft round-trip.
     */
    @PostMapping("/dev-token")
    public ResponseEntity<Map<String, Object>> devToken(
            @RequestParam(defaultValue = "dev@certguard.local") String email,
            @RequestParam(defaultValue = "google") String provider,
            @RequestParam(defaultValue = "Dev User") String name) {

        User user = userRepository.findByEmail(email).orElseGet(() ->
                userRepository.save(User.builder()
                        .providerId(provider)
                        .email(email)
                        .name(name)
                        .emailVerified(true)
                        .build()));

        var resp = tokenService.createSession(user, provider, "127.0.0.1");

        log.warn("DEV TOKEN issued for {} (provider={}). Disable auth.dev-mode in production.", email, provider);

        return ResponseEntity.ok(Map.of(
                "token",      resp.token(),
                "token_type", "Bearer",
                "expires_in", resp.expiresIn(),
                "user_id",    resp.userId(),
                "provider",   provider,
                "email",      email,
                "name",       name
        ));
    }
}
