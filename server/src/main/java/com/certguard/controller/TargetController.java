package com.certguard.controller;

import com.certguard.dto.request.CreateTargetRequest;
import com.certguard.dto.request.NotificationSettingsRequest;
import com.certguard.dto.request.UpdateTargetRequest;
import com.certguard.dto.response.NotificationSettingsResponse;
import com.certguard.dto.response.TargetResponse;
import com.certguard.security.TenantContext;
import com.certguard.service.AgentService;
import com.certguard.service.NotificationSettingsService;
import com.certguard.service.SslScannerService;
import com.certguard.service.TargetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/targets")
@RequiredArgsConstructor
public class TargetController {

    private final TargetService targetService;
    private final SslScannerService sslScannerService;
    private final AgentService agentService;
    private final NotificationSettingsService notificationSettingsService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<Page<TargetResponse>> list(Pageable pageable) {
        return ResponseEntity.ok(targetService.listTargets(TenantContext.getOrgId(), pageable));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<TargetResponse> create(@Valid @RequestBody CreateTargetRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(targetService.createTarget(TenantContext.getOrgId(), req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<TargetResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateTargetRequest req) {
        return ResponseEntity.ok(targetService.updateTarget(TenantContext.getOrgId(), id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        targetService.deleteTarget(TenantContext.getOrgId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/scan")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> scan(@PathVariable UUID id) {
        String result = targetService.triggerScan(TenantContext.getOrgId(), id, sslScannerService, agentService);
        return ResponseEntity.ok(Map.of("message", result));
    }

    @GetMapping("/{id}/scan-status")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<?> scanStatus(@PathVariable UUID id) {
        return ResponseEntity.ok(targetService.getLatestScanStatus(TenantContext.getOrgId(), id));
    }

    // ── Notification channels ──────────────────────────────────────────────

    @GetMapping("/{id}/notifications")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> getNotifications(@PathVariable UUID id) {
        TargetResponse t = targetService.getTarget(TenantContext.getOrgId(), id);
        return ResponseEntity.ok(t.getNotificationChannels() != null ? t.getNotificationChannels() : Map.of());
    }

    @PutMapping("/{id}/notifications")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<TargetResponse> updateNotifications(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> channels) {
        return ResponseEntity.ok(targetService.updateNotificationChannels(TenantContext.getOrgId(), id, channels));
    }

    // ── Notification settings (RFC 0008 §3.4) ─────────────────────────────

    /**
     * GET /api/v1/targets/{id}/notification-settings
     * Returns the per-target override if one exists; otherwise returns the effective
     * inherited value (org default or app.yml fallback) with {@code inherited: true}.
     */
    @GetMapping("/{id}/notification-settings")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<NotificationSettingsResponse> getTargetNotificationSettings(
            @PathVariable UUID id) {
        return ResponseEntity.ok(
                notificationSettingsService.getTargetSettings(TenantContext.getOrgId(), id));
    }

    /**
     * PUT /api/v1/targets/{id}/notification-settings
     * Upserts the per-target override (creates on first call, updates on subsequent calls).
     */
    @PutMapping("/{id}/notification-settings")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<NotificationSettingsResponse> putTargetNotificationSettings(
            @PathVariable UUID id,
            @Valid @RequestBody NotificationSettingsRequest req) {
        return ResponseEntity.ok(
                notificationSettingsService.upsertTargetSettings(TenantContext.getOrgId(), id, req));
    }

    /**
     * DELETE /api/v1/targets/{id}/notification-settings
     * Removes the per-target override, reverting to org default (or app.yml fallback).
     * Idempotent: returns 204 even if no override existed.
     */
    @DeleteMapping("/{id}/notification-settings")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<Void> deleteTargetNotificationSettings(@PathVariable UUID id) {
        notificationSettingsService.deleteTargetSettings(TenantContext.getOrgId(), id);
        return ResponseEntity.noContent().build();
    }
}
