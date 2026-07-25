package com.certguard.auth.service;

import com.certguard.auth.dto.request.TokenRequest;
import com.certguard.auth.dto.response.TokenResponse;
import com.certguard.auth.dto.response.ValidateResponse;
import com.certguard.auth.entity.User;
import com.certguard.auth.entity.UserSession;
import com.certguard.auth.exception.AuthException;
import com.certguard.auth.exception.ForbiddenException;
import com.certguard.auth.repository.UserRepository;
import com.certguard.auth.repository.UserSessionRepository;
import com.certguard.auth.security.UnifiedTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final UnifiedTokenProvider tokenProvider;
    private final UserSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final GoogleAuthService googleAuthService;
    private final MicrosoftAuthService microsoftAuthService;
    private final EmailAuthService emailAuthService;
    private final AuthProvisioningService provisioningService;

    /**
     * Dispatches to the correct provider, creates a session, and returns a signed unified token.
     */
    @Transactional
    public TokenResponse exchange(TokenRequest req, String clientIp) {
        User user = switch (req.provider()) {
            case "google"    -> googleAuthService.authenticateWithIdToken(req.idToken());
            case "microsoft" -> microsoftAuthService.authenticateWithCode(req.code(), req.redirectUri());
            case "email"     -> emailAuthService.authenticate(req.email(), req.password());
            default          -> throw new AuthException("Unknown provider: " + req.provider());
        };

        return createSession(user, req.provider(), clientIp);
    }

    @Transactional
    public TokenResponse createSession(User user, String provider, String clientIp) {
        UUID sessionId = UUID.randomUUID();

        OrgContextRecord ctx = null;
        try {
            ctx = provisioningService.resolveOrProvision(user.getEmail(), user.getName());
        } catch (Exception ex) {
            log.error("Failed to resolve org context for {} — JWT will have empty org claims: {}",
                    user.getEmail(), ex.getMessage());
        }

        // Derive the effective TTL from the resolved context so that the minted token,
        // the session row, and the TokenResponse.expires_in all agree.
        long effectiveTtlSeconds = tokenProvider.expirationSeconds(ctx);
        Instant expiry = Instant.now().plusSeconds(effectiveTtlSeconds);

        String jwt = tokenProvider.issue(
                user.getId(), provider, user.getEmail(), user.getName(),
                user.getProviderUserId(), sessionId, ctx);

        UserSession session = UserSession.builder()
                .id(sessionId)
                .user(user)
                .sessionToken(jwt)
                .provider(provider)
                .refreshToken(user.getRefreshToken())
                .expiresAt(expiry)
                .clientIp(clientIp)
                .build();
        sessionRepository.save(session);

        return TokenResponse.of(jwt, effectiveTtlSeconds,
                user.getId().toString(), provider, user.getEmail(), user.getName());
    }

    /**
     * RFC 0015 Phase 2 — explicit, user-driven org switch (authoritative RS256 path).
     *
     * <p>Parses the presenting token for identity, rejects platform-admin callers outright
     * (they use a separate act-as-org mechanism), delegates membership + revocation
     * validation and the {@code last_active_org_id} persistence to
     * {@link AuthProvisioningService#switchActiveOrg}, then re-mints via the normal
     * {@link #createSession} path — which re-resolves through
     * {@link AuthProvisioningService#resolveOrProvision} and will now land on
     * {@code requestedOrgId} since step 3 already made it the sticky choice. Finally
     * invalidates the OLD session row so the previous org-scoped token can't keep being used.
     *
     * @throws AuthException if the token is malformed/unparseable or the user no longer exists.
     * @throws ForbiddenException for a platform-admin caller, non-member org, or revoked
     *         membership/session (propagated from {@code switchActiveOrg}).
     */
    @Transactional
    public TokenResponse switchOrg(String oldToken, UUID requestedOrgId, String clientIp) {
        Claims claims;
        try {
            claims = tokenProvider.parse(oldToken);
        } catch (JwtException e) {
            throw new AuthException("Invalid token: " + e.getMessage());
        }

        if (Boolean.TRUE.equals(claims.get("platformAdmin", Boolean.class))) {
            throw new ForbiddenException("Platform admins switch via act-as-org, not switch-org");
        }

        String email    = claims.get("email", String.class);
        String provider = claims.get("provider", String.class);

        // Validates membership + revocation, persists last_active_org_id (RFC 0015 Phase 2).
        provisioningService.switchActiveOrg(email, requestedOrgId);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthException("User not found"));

        TokenResponse resp = createSession(user, provider, clientIp);

        // Invalidate the presenting session — the old org-scoped token must not keep working.
        sessionRepository.deleteBySessionToken(oldToken);

        return resp;
    }

    /**
     * Validates the token cryptographically and confirms the session still exists in the DB
     * (enabling server-side revocation).
     */
    @Transactional
    public ValidateResponse validate(String token) {
        try {
            Claims claims = tokenProvider.parse(token);

            UserSession session = sessionRepository.findBySessionToken(token)
                    .orElseThrow(() -> new AuthException("Session not found or revoked"));

            if (session.getExpiresAt().isBefore(Instant.now())) {
                throw new AuthException("Session has expired");
            }

            session.setLastUsedAt(Instant.now());
            sessionRepository.save(session);

            return tokenProvider.toValidateResponse(claims);
        } catch (JwtException e) {
            throw new AuthException("Invalid token: " + e.getMessage());
        }
    }

    @Transactional
    public void revokeSession(String token) {
        int deleted = sessionRepository.deleteBySessionToken(token);
        log.info("Revoked session (rows deleted: {})", deleted);
    }

    /** Revokes every active session for the user who owns the given token. */
    @Transactional
    public void revokeAllSessions(String token) {
        Claims claims = tokenProvider.parse(token);
        UUID userId = UUID.fromString(claims.getSubject());
        int deleted = sessionRepository.deleteAllByUserId(userId);
        log.info("Revoked all {} sessions for user {}", deleted, userId);
    }

    @Scheduled(fixedDelayString = "${auth.session.cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanupExpiredSessions() {
        int deleted = sessionRepository.deleteExpiredSessions(Instant.now());
        if (deleted > 0) {
            log.debug("Purged {} expired sessions", deleted);
        }
    }
}
