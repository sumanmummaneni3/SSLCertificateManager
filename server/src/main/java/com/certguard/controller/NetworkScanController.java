package com.certguard.controller;

import com.certguard.dto.request.NetworkScanCreateRequest;
import com.certguard.dto.response.DiscoveredEndpointResponse;
import com.certguard.dto.response.NetworkScanResponse;
import com.certguard.enums.DeviceClass;
import com.certguard.enums.EndpointPortState;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.service.NetworkScanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST endpoints for authenticated network sweep jobs (RFC 0011 Part A).
 * All paths are under /api/v1/organizations/{orgId}/network-scans.
 * Role checks via @PreAuthorize; tenant isolation via canAccessOrg.
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/network-scans")
@RequiredArgsConstructor
public class NetworkScanController {

    private final NetworkScanService networkScanService;

    /**
     * POST /api/v1/organizations/{orgId}/network-scans
     * Creates and enqueues a network sweep for an org-owned agent.
     * Requires ADMIN or ENGINEER role.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER') and @mspAccessGuard.canAccessOrg(#orgId)")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public NetworkScanResponse createScan(
            @PathVariable UUID orgId,
            @RequestBody @Valid NetworkScanCreateRequest req,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) {
        return networkScanService.createScan(orgId, req, principal.getUserId());
    }

    /**
     * GET /api/v1/organizations/{orgId}/network-scans
     * Lists all sweeps for the org, newest first (paginated).
     */
    @GetMapping
    @PreAuthorize("@mspAccessGuard.canAccessOrg(#orgId)")
    public Page<NetworkScanResponse> listScans(
            @PathVariable UUID orgId,
            Pageable pageable) {
        return networkScanService.listScans(orgId, pageable);
    }

    /**
     * GET /api/v1/organizations/{orgId}/network-scans/{scanId}
     * Returns status and counters for a single sweep.
     */
    @GetMapping("/{scanId}")
    @PreAuthorize("@mspAccessGuard.canAccessOrg(#orgId)")
    public NetworkScanResponse getScan(
            @PathVariable UUID orgId,
            @PathVariable UUID scanId) {
        return networkScanService.getScan(orgId, scanId);
    }

    /**
     * GET /api/v1/organizations/{orgId}/network-scans/{scanId}/endpoints
     * Lists discovered endpoints for a sweep (paginated, filterable by state and deviceClass).
     */
    @GetMapping("/{scanId}/endpoints")
    @PreAuthorize("@mspAccessGuard.canAccessOrg(#orgId)")
    public Page<DiscoveredEndpointResponse> listEndpoints(
            @PathVariable UUID orgId,
            @PathVariable UUID scanId,
            @RequestParam(required = false) EndpointPortState state,
            @RequestParam(required = false) DeviceClass deviceClass,
            Pageable pageable) {
        return networkScanService.listEndpoints(orgId, scanId, state, deviceClass, pageable);
    }

    /**
     * DELETE /api/v1/organizations/{orgId}/network-scans/{scanId}
     * Cancels a PENDING or IN_PROGRESS sweep. Requires ADMIN role.
     */
    @DeleteMapping("/{scanId}")
    @PreAuthorize("hasRole('ADMIN') and @mspAccessGuard.canAccessOrg(#orgId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelScan(
            @PathVariable UUID orgId,
            @PathVariable UUID scanId) {
        networkScanService.cancelScan(orgId, scanId);
    }
}
