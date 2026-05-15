package com.certguard.auth.controller;

import com.certguard.auth.dto.response.UserResponse;
import com.certguard.auth.entity.User;
import com.certguard.auth.exception.AuthException;
import com.certguard.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    /**
     * GET /api/users/me
     * Returns the authenticated user's profile. Requires a valid Bearer token.
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("User not found"));
        return ResponseEntity.ok(UserResponse.from(user));
    }
}
