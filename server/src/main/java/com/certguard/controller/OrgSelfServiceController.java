package com.certguard.controller;

import com.certguard.dto.request.RequestMspUpgradeRequest;
import com.certguard.dto.request.RequestQuotaIncreaseRequest;
import com.certguard.entity.Organization;
import com.certguard.entity.Subscription;
import com.certguard.enums.SalesWebhookEventType;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.SubscriptionRepository;
import com.certguard.security.TenantContext;
import com.certguard.service.SalesWebhookService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/org", produces = "application/json")
public class OrgSelfServiceController {

    private final SalesWebhookService salesWebhookService;
    private final OrganizationRepository organizationRepository;
    private final SubscriptionRepository subscriptionRepository;

    public OrgSelfServiceController(SalesWebhookService salesWebhookService,
                                    OrganizationRepository organizationRepository,
                                    SubscriptionRepository subscriptionRepository) {
        this.salesWebhookService    = salesWebhookService;
        this.organizationRepository = organizationRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @PostMapping("/request-msp-upgrade")
    @PreAuthorize("hasAnyRole('ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Void> requestMspUpgrade(@Valid @RequestBody RequestMspUpgradeRequest req) {
        UUID orgId = TenantContext.getOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId).orElse(null);
        String actorEmail = SecurityContextHolder.getContext().getAuthentication().getName();

        salesWebhookService.fire(
                SalesWebhookEventType.MSP_UPGRADE_REQUESTED,
                org, sub, actorEmail,
                Map.of("reason", req.reason() != null ? req.reason() : ""));

        return ResponseEntity.accepted().build();
    }

    @PostMapping("/request-quota-increase")
    @PreAuthorize("hasAnyRole('ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Void> requestQuotaIncrease(@Valid @RequestBody RequestQuotaIncreaseRequest req) {
        UUID orgId = TenantContext.getOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId).orElse(null);
        String actorEmail = SecurityContextHolder.getContext().getAuthentication().getName();

        salesWebhookService.fire(
                SalesWebhookEventType.QUOTA_INCREASE_REQUESTED,
                org, sub, actorEmail,
                Map.of("requestedQuota", req.requestedQuota(),
                       "reason", req.reason() != null ? req.reason() : ""));

        return ResponseEntity.accepted().build();
    }
}
