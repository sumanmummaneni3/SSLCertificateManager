package com.certguard.controller;

import com.certguard.dto.admin.AdminOrgDetailDto;
import com.certguard.dto.admin.AdminOrgDto;
import com.certguard.dto.admin.AdminOrgTreeDto;
import com.certguard.dto.response.OrgResponse;
import com.certguard.entity.PlatformAdminAudit;
import com.certguard.service.AdminService;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import com.certguard.security.CertGuardUserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Platform-Admin–only REST surface.
 * All endpoints under /api/v1/admin/ require the PLATFORM_ADMIN role.
 */
@Validated
@RestController
@RequestMapping(value = "/api/v1/admin", produces = "application/json")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * Flat list of all organisations with counts and subscription info.
     */
    @GetMapping("/orgs")
    public ResponseEntity<List<AdminOrgDto>> listOrgs() {
        return ResponseEntity.ok(adminService.listAllOrgs());
    }

    /**
     * Org hierarchy: MSP orgs at root with nested clients[]; SINGLE orgs at root with empty clients[].
     */
    @GetMapping("/orgs/tree")
    public ResponseEntity<List<AdminOrgTreeDto>> getOrgTree() {
        return ResponseEntity.ok(adminService.getOrgTree());
    }

    /**
     * Single org detail including address/contact fields.
     */
    @GetMapping("/orgs/{orgId}")
    public ResponseEntity<AdminOrgDetailDto> getOrg(@PathVariable UUID orgId) {
        return ResponseEntity.ok(adminService.getOrgDetail(orgId));
    }

    /**
     * All MSP-type orgs.
     */
    @GetMapping("/msps")
    public ResponseEntity<List<AdminOrgDto>> listMsps() {
        return ResponseEntity.ok(adminService.listMsps());
    }

    /**
     * Update certificate quota for any org.
     * Delegates to existing OrgService quota logic — single source of truth.
     */
    @PutMapping("/orgs/{orgId}/quota")
    public ResponseEntity<OrgResponse> updateQuota(
            @PathVariable UUID orgId,
            @Min(1) @RequestParam int value) {
        return ResponseEntity.ok(adminService.updateQuota(orgId, value));
    }

    @PatchMapping("/orgs/{orgId}/promote-msp")
    public ResponseEntity<OrgResponse> promoteToMsp(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(adminService.promoteToMsp(orgId, reason));
    }

    @PatchMapping("/orgs/{orgId}/demote-msp")
    public ResponseEntity<OrgResponse> demoteFromMsp(@PathVariable UUID orgId) {
        return ResponseEntity.ok(adminService.demoteFromMsp(orgId));
    }

    @DeleteMapping("/orgs/{orgId}")
    public ResponseEntity<Void> archiveOrg(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) {
        adminService.archiveOrg(orgId, principal.getUserId(), reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/orgs/{orgId}/restore")
    public ResponseEntity<OrgResponse> restoreOrg(@PathVariable UUID orgId) {
        return ResponseEntity.ok(adminService.restoreOrg(orgId));
    }

    /**
     * Paginated platform-admin audit log.
     * Optional filters: orgId, from (ISO-8601), to (ISO-8601).
     */
    @GetMapping("/audit")
    public ResponseEntity<Page<PlatformAdminAudit>> getAudit(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(adminService.listAuditEvents(orgId, from, to, pageable));
    }
}
