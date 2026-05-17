package com.certguard.auth.service;

import com.certguard.auth.dto.request.EmailRegisterRequest;
import com.certguard.auth.entity.User;
import com.certguard.auth.exception.AuthException;
import com.certguard.auth.exception.ConflictException;
import com.certguard.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailAuthService {

    private static final String PROVIDER = "email";
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;

    @Transactional
    public User register(EmailRegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new ConflictException("An account with this email already exists");
        }

        String token = generateToken();

        User user = User.builder()
                .providerId(PROVIDER)
                .email(req.email())
                .name(req.name() != null ? req.name() : req.email().split("@")[0])
                .passwordHash(passwordEncoder.encode(req.password()))
                .emailVerified(false)
                .emailVerificationToken(token)
                .emailVerificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();

        userRepository.save(user);
        mailService.sendVerificationEmail(user.getEmail(), token);
        log.info("New email account registered for {}, verification email sent", user.getEmail());
        return user;
    }

    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByEmailVerificationToken(token)
                .orElseThrow(() -> new AuthException("Invalid or expired verification link"));

        if (user.getEmailVerificationExpiresAt().isBefore(Instant.now())) {
            throw new AuthException("Verification link has expired — please request a new one");
        }

        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationExpiresAt(null);
        userRepository.save(user);
        log.info("Email verified for {}", user.getEmail());
    }

    @Transactional
    public void resendVerification(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.isEmailVerified() || !PROVIDER.equals(user.getProviderId())) return;

            String token = generateToken();
            user.setEmailVerificationToken(token);
            user.setEmailVerificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
            userRepository.save(user);
            mailService.sendVerificationEmail(email, token);
            log.info("Verification email resent to {}", email);
        });
        // Always return silently — don't reveal whether the email exists.
    }

    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            if (!PROVIDER.equals(user.getProviderId())) return;

            String token = generateToken();
            user.setPasswordResetToken(token);
            user.setPasswordResetExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
            userRepository.save(user);
            mailService.sendPasswordResetEmail(email, token);
            log.info("Password reset email sent to {}", email);
        });
        // Always return silently — don't reveal whether the email exists.
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        User user = userRepository.findByPasswordResetToken(token)
                .orElseThrow(() -> new AuthException("Invalid or expired reset link"));

        if (user.getPasswordResetExpiresAt().isBefore(Instant.now())) {
            throw new AuthException("Reset link has expired — please request a new one");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiresAt(null);
        // Completing a password reset also verifies the email address.
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            user.setEmailVerificationToken(null);
            user.setEmailVerificationExpiresAt(null);
        }
        userRepository.save(user);
        log.info("Password reset completed for {}", user.getEmail());
    }

    @Transactional(readOnly = true)
    public User authenticate(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthException("Invalid credentials"));

        if (!PROVIDER.equals(user.getProviderId())) {
            throw new AuthException("This account uses " + user.getProviderId() + " login");
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthException("Invalid credentials");
        }
        if (!user.isEmailVerified()) {
            throw new AuthException("EMAIL_NOT_VERIFIED");
        }
        return user;
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
