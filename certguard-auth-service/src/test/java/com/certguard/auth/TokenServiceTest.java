package com.certguard.auth;

import com.certguard.auth.dto.response.ValidateResponse;
import com.certguard.auth.entity.User;
import com.certguard.auth.entity.UserSession;
import com.certguard.auth.exception.AuthException;
import com.certguard.auth.repository.UserSessionRepository;
import com.certguard.auth.security.UnifiedTokenProvider;
import com.certguard.auth.service.*;
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

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair pair = gen.generateKeyPair();

        tokenProvider = new UnifiedTokenProvider();
        ReflectionTestUtils.setField(tokenProvider, "privateKey", pair.getPrivate());
        ReflectionTestUtils.setField(tokenProvider, "publicKey", (RSAPublicKey) pair.getPublic());
        ReflectionTestUtils.setField(tokenProvider, "expirationMs", 3_600_000L);
        ReflectionTestUtils.setField(tokenService, "tokenProvider", tokenProvider);
    }

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
        assertThat(resp.expiresIn()).isEqualTo(3600L);
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
                .expiresAt(Instant.now().plusSeconds(3600))
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
}
