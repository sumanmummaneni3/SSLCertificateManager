package com.certguard.controller;

import com.certguard.dto.request.NotificationSettingsRequest;
import com.certguard.dto.request.UpdateOrgProfileRequest;
import com.certguard.dto.response.NotificationSettingsResponse;
import com.certguard.dto.response.OrgResponse;
import com.certguard.security.TenantContext;
import com.certguard.service.NotificationSettingsService;
import com.certguard.service.OrgService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping(value = "/api/v1/org", produces = "application/json")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;
    private final NotificationSettingsService notificationSettingsService;

    // ── Org-user endpoints ─────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<OrgResponse> getOrg() {
        return ResponseEntity.ok(orgService.getOrg(TenantContext.getOrgId()));
    }

    /** Full profile endpoint — returns all fields including address, phone, email */
    @GetMapping("/profile")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<OrgResponse> getProfile() {
        return ResponseEntity.ok(orgService.getOrg(TenantContext.getOrgId()));
    }

    @PutMapping("/profile")
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<OrgResponse> updateProfile(@Valid @RequestBody UpdateOrgProfileRequest req) {
        return ResponseEntity.ok(orgService.updateProfile(TenantContext.getOrgId(), req));
    }

    /** Legacy endpoint — kept for backward compat */
    @PutMapping("/name")
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<OrgResponse> updateName(@RequestParam String name) {
        return ResponseEntity.ok(orgService.updateName(TenantContext.getOrgId(), name));
    }

    // ── Notification settings (RFC 0008 §3.4) ─────────────────────────────

    /**
     * GET /api/v1/org/notification-settings
     * Returns the org-default notification policy (falls back to app.yml defaults if no row exists).
     */
    @GetMapping("/notification-settings")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<NotificationSettingsResponse> getOrgNotificationSettings() {
        return ResponseEntity.ok(
                notificationSettingsService.getOrgSettings(TenantContext.getOrgId()));
    }

    /**
     * PUT /api/v1/org/notification-settings
     * Upserts the org-default notification policy (creates on first call, updates on subsequent calls).
     */
    @PutMapping("/notification-settings")
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<NotificationSettingsResponse> putOrgNotificationSettings(
            @Valid @RequestBody NotificationSettingsRequest req) {
        return ResponseEntity.ok(
                notificationSettingsService.upsertOrgSettings(TenantContext.getOrgId(), req));
    }

    // ── Platform Admin endpoints ───────────────────────────────────────────

    // Deprecated: use /api/v1/admin/orgs instead
    @Deprecated
    @GetMapping("/admin/orgs")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<List<OrgResponse>> listAllOrgs() {
        return ResponseEntity.ok(orgService.listAllOrgs());
    }

    // Deprecated: use /api/v1/admin/orgs/{orgId}/quota instead
    @Deprecated
    @PutMapping("/admin/orgs/{orgId}/quota")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OrgResponse> updateQuota(@PathVariable UUID orgId, @Min(1) @RequestParam int value) {
        return ResponseEntity.ok(orgService.updateCertificateQuota(orgId, value));
    }
}
