package com.certguard.auth;

import com.certguard.auth.dto.response.ValidateResponse;
import com.certguard.auth.entity.User;
import com.certguard.auth.entity.UserSession;
import com.certguard.auth.exception.AuthException;
import com.certguard.auth.repository.UserSessionRepository;
import com.certguard.auth.security.UnifiedTokenProvider;
import com.certguard.auth.service.*;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock UserSessionRepository sessionRepository;
    @Mock GoogleAuthService googleAuthService;
    @Mock MicrosoftAuthService microsoftAuthService;
    @Mock EmailAuthService emailAuthService;
    @Mock AuthProvisioningService provisioningService;

    @InjectMocks TokenService tokenService;

    private UnifiedTokenProvider tokenProvider;

    /** Normal-user TTL used in tests: 24 hours in milliseconds. */
    private static final long NORMAL_USER_EXPIRATION_MS = 86_400_000L;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair pair = gen.generateKeyPair();

        tokenProvider = new UnifiedTokenProvider();
        ReflectionTestUtils.setField(tokenProvider, "privateKey", pair.getPrivate());
        ReflectionTestUtils.setField(tokenProvider, "publicKey", (RSAPublicKey) pair.getPublic());
        ReflectionTestUtils.setField(tokenProvider, "expirationMs", NORMAL_USER_EXPIRATION_MS);
        ReflectionTestUtils.setField(tokenService, "tokenProvider", tokenProvider);
    }

    // -------------------------------------------------------------------------
    // Existing tests (updated expiry assertion from 1 h to 24 h)
    // -------------------------------------------------------------------------

    @Test
    void createSession_persistsSessionAndReturnsToken() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("alice@example.com")
                .name("Alice")
                .providerId("email")
                .build();

        when(sessionRepository.save(any(UserSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var resp = tokenService.createSession(user, "email", "127.0.0.1");

        assertThat(resp.token()).isNotBlank();
        assertThat(resp.provider()).isEqualTo("email");
        assertThat(resp.email()).isEqualTo("alice@example.com");
        // Default TTL is now 24 h = 86 400 s
        assertThat(resp.expiresIn()).isEqualTo(86_400L);
        verify(sessionRepository).save(any(UserSession.class));
    }

    @Test
    void validate_raisesAuthException_whenSessionMissing() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("bob@example.com")
                .name("Bob")
                .providerId("google")
                .build();
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var resp = tokenService.createSession(user, "google", "10.0.0.1");

        when(sessionRepository.findBySessionToken(resp.token())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokenService.validate(resp.token()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("Session not found");
    }

    @Test
    void validate_success_returnsValidResponse() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("carol@example.com")
                .name("Carol")
                .providerId("google")
                .build();
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var tokenResp = tokenService.createSession(user, "google", "10.0.0.2");

        UserSession session = UserSession.builder()
                .sessionToken(tokenResp.token())
                .expiresAt(Instant.now().plusSeconds(86_400))
                .build();
        when(sessionRepository.findBySessionToken(tokenResp.token()))
                .thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenReturn(session);

        ValidateResponse vr = tokenService.validate(tokenResp.token());

        assertThat(vr.valid()).isTrue();
        assertThat(vr.email()).isEqualTo("carol@example.com");
        assertThat(vr.provider()).isEqualTo("google");
    }

    @Test
    void issueToken_usesRS256andCorrectIssuer() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("dave@example.com")
                .name("Dave")
                .providerId("email")
                .build();
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var resp = tokenService.createSession(user, "email", "127.0.0.1");

        var claims = tokenProvider.parse(resp.token());
        assertThat(claims.getIssuer()).isEqualTo(UnifiedTokenProvider.ISSUER);
        assertThat(claims.get("email", String.class)).isEqualTo("dave@example.com");
    }

    // -------------------------------------------------------------------------
    // Role-aware TTL policy tests
    // -------------------------------------------------------------------------

    /**
     * Normal (non-platform-admin) users must receive a ~24 h exp claim.
     * We accept up to 5 s of clock drift between token mint and assertion.
     */
    @Test
    void normalUser_tokenExpiry_is24Hours() {
        UUID userId    = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        // ctx with platformAdmin = false
        OrgContextRecord ctx = new OrgContextRecord(userId, UUID.randomUUID(), "member", false);

        Instant before = Instant.now();
        String jwt = tokenProvider.issue(userId, "email", "normal@example.com", "Normal",
                null, sessionId, ctx);
        Instant after = Instant.now();

        Claims claims = tokenProvider.parse(jwt);
        Instant exp = claims.getExpiration().toInstant();

        // exp should be ~24 h from now
        assertThat(exp).isAfterOrEqualTo(before.plus(24, ChronoUnit.HOURS).minusSeconds(5));
        assertThat(exp).isBeforeOrEqualTo(after.plus(24, ChronoUnit.HOURS).plusSeconds(5));
        assertThat(claims.get("platformAdmin", Boolean.class)).isFalse();
    }

    /**
     * Platform-admin users must receive a far-future exp (100 years from now),
     * and the corresponding expirationSeconds(ctx) must reflect that sentinel value.
     */
    @Test
    void platformAdmin_tokenExpiry_isFarFuture() {
        UUID userId    = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        // ctx with platformAdmin = true
        OrgContextRecord ctx = new OrgContextRecord(userId, null, null, true);

        Instant before = Instant.now();
        String jwt = tokenProvider.issue(userId, "email", "admin@certguard.cloud", "Admin",
                null, sessionId, ctx);
        Instant after = Instant.now();

        Claims claims = tokenProvider.parse(jwt);
        Instant exp = claims.getExpiration().toInstant();

        // exp must be at least 99 years in the future (generous tolerance for any clock lag)
        assertThat(exp).isAfter(before.plus(99L * 365, ChronoUnit.DAYS));
        // exp must not exceed 101 years (sanity upper bound)
        assertThat(exp).isBefore(after.plus(101L * 365, ChronoUnit.DAYS));
        assertThat(claims.get("platformAdmin", Boolean.class)).isTrue();

        // expirationSeconds(ctx) must return the admin sentinel value
        long adminTtlSeconds = tokenProvider.expirationSeconds(ctx);
        assertThat(adminTtlSeconds).isEqualTo(UnifiedTokenProvider.ADMIN_NON_EXPIRING_TTL_MS / 1000);
    }

    /**
     * When createSession is called for a platform-admin user, the TokenResponse.expires_in
     * must match the far-future sentinel, not the normal 24 h TTL.
     */
    @Test
    void createSession_platformAdmin_expiresInIsFarFuture() {
        UUID userId = UUID.randomUUID();
        OrgContextRecord adminCtx = new OrgContextRecord(userId, null, null, true);

        User adminUser = User.builder()
                .id(userId)
                .email("admin@certguard.cloud")
                .name("Platform Admin")
                .providerId("email")
                .build();

        when(sessionRepository.save(any(UserSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        try {
            when(provisioningService.resolveOrProvision(anyString(), anyString()))
                    .thenReturn(adminCtx);
        } catch (Exception ignored) { }

        var resp = tokenService.createSession(adminUser, "email", "127.0.0.1");

        long expectedAdminTtl = UnifiedTokenProvider.ADMIN_NON_EXPIRING_TTL_MS / 1000;
        assertThat(resp.expiresIn()).isEqualTo(expectedAdminTtl);

        // The minted token must also carry the far-future exp
        Claims claims = tokenProvider.parse(resp.token());
        Instant exp = claims.getExpiration().toInstant();
        assertThat(exp).isAfter(Instant.now().plus(99L * 365, ChronoUnit.DAYS));
    }

    /**
     * expirationSeconds() (no-arg) must still return the normal-user TTL (24 h).
     */
    @Test
    void expirationSeconds_noArg_returnsNormalUserTtl() {
        assertThat(tokenProvider.expirationSeconds()).isEqualTo(NORMAL_USER_EXPIRATION_MS / 1000);
    }

    /**
     * expirationSeconds(ctx) with a non-admin ctx returns the normal-user TTL.
     */
    @Test
    void expirationSeconds_nonAdminCtx_returnsNormalUserTtl() {
        OrgContextRecord ctx = new OrgContextRecord(UUID.randomUUID(), UUID.randomUUID(), "owner", false);
        assertThat(tokenProvider.expirationSeconds(ctx)).isEqualTo(NORMAL_USER_EXPIRATION_MS / 1000);
    }

    /**
     * expirationSeconds(null) falls through to the normal-user TTL (ctx == null means
     * org resolution failed, which should not elevate privileges).
     */
    @Test
    void expirationSeconds_nullCtx_returnsNormalUserTtl() {
        assertThat(tokenProvider.expirationSeconds(null)).isEqualTo(NORMAL_USER_EXPIRATION_MS / 1000);
    }
}
