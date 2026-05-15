package com.certguard.controller;

import com.certguard.dto.request.CreateTargetRequest;
import com.certguard.dto.response.TargetResponse;
import com.certguard.service.TargetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Org-scoped target endpoints.
 * <p>
 * Provides MSP admins and platform admins with a multi-tenant URL hierarchy for
 * target management so they can operate on child orgs without switching context.
 * The path {@code orgId} overrides the caller's TenantContext org, and access is
 * gated by {@code MspAccessGuard} which ensures the caller's home org is either
 * the target org itself or its direct MSP parent.
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/targets")
public class OrgTargetController {

    private final TargetService targetService;

    public OrgTargetController(TargetService targetService) {
        this.targetService = targetService;
    }

    /**
     * POST /api/v1/organizations/{orgId}/targets
     * <p>
     * Creates a target under the specified org. Callable by ADMIN, ENGINEER, or
     * PLATFORM_ADMIN provided the caller can access {@code orgId} per
     * {@code MspAccessGuard.canAccessOrg}.
     *
     * @param orgId the tenant org under which the target is created
     * @param req   validated target creation payload; must not embed an orgId —
     *              the path parameter is authoritative
     * @return 201 Created with the persisted {@link TargetResponse}
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN') and @mspAccessGuard.canAccessOrg(#orgId)")
    public ResponseEntity<TargetResponse> createForOrg(
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateTargetRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(targetService.createTarget(orgId, req));
    }
}
