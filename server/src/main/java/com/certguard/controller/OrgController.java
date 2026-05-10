package com.certguard.controller;

import com.certguard.dto.request.UpdateOrgProfileRequest;
import com.certguard.dto.response.OrgResponse;
import com.certguard.security.TenantContext;
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
