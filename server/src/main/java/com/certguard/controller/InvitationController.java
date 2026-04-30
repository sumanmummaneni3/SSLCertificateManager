package com.certguard.controller;

import com.certguard.dto.request.AcceptInviteRequest;
import com.certguard.service.InvitationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /**
     * Step 1: Validate invite token and send OTP.
     * POST /api/v1/auth/invite/validate?token=...
     */
    @PostMapping("/invite/validate")
    public ResponseEntity<Map<String, String>> validateInvite(@RequestParam String token) {
        String email = invitationService.validateInviteAndSendOtp(token);
        return ResponseEntity.ok(Map.of(
            "email", email,
            "message", "OTP sent to " + email));
    }

    /**
     * Step 2: Submit email + OTP to complete invite acceptance.
     * POST /api/v1/auth/invite/accept
     */
    @PostMapping("/invite/accept")
    public ResponseEntity<Map<String, String>> acceptInvite(@Valid @RequestBody AcceptInviteRequest req) {
        Map<String, String> result = invitationService.acceptInvite(
                req.getToken(), req.getEmail(), req.getOtp());
        return ResponseEntity.ok(result);
    }
}
