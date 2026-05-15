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

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailAuthService {

    private static final String PROVIDER = "email";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User register(EmailRegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new ConflictException("An account with this email already exists");
        }
        User user = User.builder()
                .providerId(PROVIDER)
                .email(req.email())
                .name(req.name() != null ? req.name() : req.email().split("@")[0])
                .passwordHash(passwordEncoder.encode(req.password()))
                .emailVerified(false)
                .build();
        return userRepository.save(user);
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
        return user;
    }
}
