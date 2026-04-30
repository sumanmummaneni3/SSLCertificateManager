package com.certguard.controller;

import com.certguard.entity.User;
import com.certguard.enums.UserRole;
import com.certguard.security.JwtTokenProvider;
import com.certguard.service.OrgService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "app.dev-mode", havingValue = "true")
public class DevAuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final OrgService orgService;

    public DevAuthController(JwtTokenProvider jwtTokenProvider,
                             OrgService orgService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.orgService       = orgService;
    }

    @PostMapping("/dev-token")
    public ResponseEntity<?> devToken(
            @RequestParam(defaultValue = "admin@certguard.local") String email,
            @RequestParam(defaultValue = "ADMIN") String role) {

        UserRole userRole;
        try {
            userRole = UserRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown role: " + role));
        }

        User user = orgService.provisionDevUser(email, userRole);

        String token = jwtTokenProvider.generateToken(
                user.getId(), user.getOrganization().getId(), user.getEmail(), user.getRole().name());

        log.info("Dev token issued for: {} (role={})", email, user.getRole());
        return ResponseEntity.ok(Map.of(
                "token", token,
                "orgId", user.getOrganization().getId(),
                "email", email,
                "role",  user.getRole().name(),
                "name",  user.getName() != null ? user.getName() : email
        ));
    }

}
