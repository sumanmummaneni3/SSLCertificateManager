package com.certguard.controller;

import com.certguard.dto.internal.InternalRenewalNotificationRequest;
import com.certguard.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Internal REST API for certguard-renewal-service to trigger email notifications.
 * Secured via InternalServiceAuthFilter (Bearer token).
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1/notifications")
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL_SERVICE')")
public class InternalRenewalNotificationController {

    private final NotificationService notificationService;

    @PostMapping("/renewal")
    public ResponseEntity<Void> sendRenewalNotification(
            @RequestBody InternalRenewalNotificationRequest req) {

        log.info("Internal notification request: eventType={}, renewalId={}",
                req.eventType(), req.renewalId());

        switch (req.eventType()) {
            case "RENEWAL_READY"     -> notificationService.dispatchRenewalReady(req);
            case "RENEWAL_INSTALLED" -> notificationService.dispatchRenewalInstalled(req);
            case "RENEWAL_FAILED"    -> notificationService.dispatchRenewalFailed(req);
            default -> log.warn("Unknown renewal notification eventType: {}", req.eventType());
        }

        return ResponseEntity.accepted().build();
    }
}
