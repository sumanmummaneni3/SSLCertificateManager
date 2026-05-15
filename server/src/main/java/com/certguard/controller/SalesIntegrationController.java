package com.certguard.controller;

import com.certguard.dto.sales.SalesOrgDetailDto;
import com.certguard.dto.sales.SalesOrgSummaryDto;
import com.certguard.dto.sales.SalesSubscriptionDto;
import com.certguard.dto.sales.UpdateQuotaRequest;
import com.certguard.dto.sales.UpdateSubscriptionStatusRequest;
import com.certguard.enums.OrgType;
import com.certguard.enums.SubscriptionStatus;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.service.OrgService;
import com.certguard.service.SalesService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Internal machine-to-machine REST surface for the external Sales application.
 * All endpoints require a valid sales API key authenticated via {@link com.certguard.security.SalesAuthFilter}.
 */
@RestController
@RequestMapping(value = "/api/internal/v1/sales", produces = "application/json")
@PreAuthorize("hasRole('SALES_APP')")
public class SalesIntegrationController {

    private static final String SALES_KEY_LABEL_ATTR = "salesKeyLabel";

    private final SalesService salesService;
    private final OrgService orgService;

    public SalesIntegrationController(SalesService salesService, OrgService orgService) {
        this.salesService = salesService;
        this.orgService   = orgService;
    }

    /**
     * Health check for the sales integration.
     * Returns a simple JSON body confirming authentication is working.
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        return ResponseEntity.ok(Map.of("ok", true, "ts", Instant.now()));
    }

    /**
     * Paginated list of organisations.
     *
     * @param status   optional subscription status filter
     * @param orgType  optional org type filter
     * @param page     zero-based page number (default 0)
     * @param size     page size (default 25)
     */
    @GetMapping("/orgs")
    public ResponseEntity<Page<SalesOrgSummaryDto>> listOrgs(
            @RequestParam(required = false) SubscriptionStatus status,
            @RequestParam(required = false) OrgType orgType,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<SalesOrgSummaryDto> result = salesService.listOrgs(status, orgType,
                PageRequest.of(page, size));
        return ResponseEntity.ok(result);
    }

    /**
     * Full detail for a single organisation.
     */
    @GetMapping("/orgs/{orgId}")
    public ResponseEntity<SalesOrgDetailDto> getOrgDetail(@PathVariable UUID orgId) {
        return ResponseEntity.ok(salesService.getOrgDetail(orgId));
    }

    /**
     * Update the certificate quota for an organisation.
     * The org must exist and must not be archived.
     */
    @PatchMapping("/orgs/{orgId}/quota")
    public ResponseEntity<SalesSubscriptionDto> updateQuota(
            @PathVariable UUID orgId,
            @Valid @RequestBody UpdateQuotaRequest req,
            HttpServletRequest httpRequest) {

        SalesOrgDetailDto org = salesService.getOrgDetail(orgId);
        if (org.getArchivedAt() != null) {
            throw new ResourceNotFoundException("Organization is archived: " + orgId);
        }

        // Delegate to OrgService — single source of truth for quota logic
        orgService.updateCertificateQuota(orgId, req.getMaxCertificateQuota());

        // Build response from the updated subscription
        SalesOrgDetailDto updated = salesService.getOrgDetail(orgId);
        String keyLabel = (String) httpRequest.getAttribute(SALES_KEY_LABEL_ATTR);

        SalesSubscriptionDto dto = SalesSubscriptionDto.builder()
                .orgId(orgId)
                .status(updated.getSubscriptionStatus())
                .maxCertificateQuota(updated.getMaxCertificateQuota())
                .updatedAt(Instant.now())
                .build();

        return ResponseEntity.ok(dto);
    }

    /**
     * Update the subscription status for an organisation.
     * Validates the state machine transition and writes an audit row.
     */
    @PatchMapping("/orgs/{orgId}/subscription")
    public ResponseEntity<SalesSubscriptionDto> updateSubscription(
            @PathVariable UUID orgId,
            @Valid @RequestBody UpdateSubscriptionStatusRequest req,
            HttpServletRequest httpRequest) {

        String keyLabel = (String) httpRequest.getAttribute(SALES_KEY_LABEL_ATTR);
        SalesSubscriptionDto result = salesService.updateSubscriptionStatus(
                orgId, req.getStatus(), req.getReason(), keyLabel);
        return ResponseEntity.ok(result);
    }

    /**
     * Promote an org to MSP type and activate its subscription in a single operation.
     */
    @PostMapping("/orgs/{orgId}/activate-msp")
    public ResponseEntity<SalesOrgDetailDto> activateMsp(
            @PathVariable UUID orgId,
            HttpServletRequest httpRequest) {

        String keyLabel = (String) httpRequest.getAttribute(SALES_KEY_LABEL_ATTR);
        SalesOrgDetailDto result = salesService.activateMsp(orgId, keyLabel);
        return ResponseEntity.ok(result);
    }
}
