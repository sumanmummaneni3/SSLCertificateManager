package com.certguard.controller;

import com.certguard.dto.response.NotificationDeliveryStatusResponse;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.service.NotificationDeliveryStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Org-scoped email delivery-status endpoint — backs a UI banner that warns users when
 * expiry/revocation alert emails are not being delivered (R19 durable outbox, V43).
 */
@RestController
@RequestMapping(value = "/api/v1/organizations/{orgId}/notifications", produces = "application/json")
public class NotificationDeliveryStatusController {

    private final NotificationDeliveryStatusService deliveryStatusService;

    public NotificationDeliveryStatusController(NotificationDeliveryStatusService deliveryStatusService) {
        this.deliveryStatusService = deliveryStatusService;
    }

    /**
     * GET /api/v1/organizations/{orgId}/notifications/delivery-status
     *
     * <p>Callable by any authenticated member of {@code orgId} (ADMIN/ENGINEER/VIEWER/
     * PLATFORM_ADMIN) — the banner is shown to the whole org, not just admins. {@code lastError}
     * carries raw SMTP diagnostics and is only populated when the caller holds an org-admin
     * role; it is {@code null} for all other roles. Recipient email addresses are never
     * included, for any role. Access to {@code orgId} itself (incl. cross-org isolation) is
     * enforced by {@code MspAccessGuard.canAccessOrg}, matching every other org-scoped
     * controller.
     */
    @GetMapping("/delivery-status")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN') and @mspAccessGuard.canAccessOrg(#orgId)")
    public ResponseEntity<NotificationDeliveryStatusResponse> deliveryStatus(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) {
        boolean isAdmin = principal.isPlatformAdmin() || "ADMIN".equals(principal.getOrgRole());
        return ResponseEntity.ok(deliveryStatusService.getDeliveryStatus(orgId, isAdmin));
    }
}
