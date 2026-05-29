package com.certguard.controller;

import com.certguard.client.RenewalServiceClient;
import com.certguard.dto.internal.InternalCreateRenewalRequest;
import com.certguard.dto.request.RequestRenewalRequest;
import com.certguard.dto.response.RenewalResponse;
import com.certguard.entity.CertificateRecord;
import com.certguard.entity.Target;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.security.TenantContext;
import com.certguard.service.SubscriptionGuard;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(value = "/api/v1", produces = "application/json")
@RequiredArgsConstructor
public class CertificateRenewalController {

    private final CertificateRecordRepository certRepository;
    private final SubscriptionGuard subscriptionGuard;
    private final RenewalServiceClient renewalServiceClient;

    /**
     * POST /api/v1/certificates/{certId}/renewals
     * Validates cert + agent in core, then delegates renewal creation to renewal service.
     */
    @PostMapping("/certificates/{certId}/renewals")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<RenewalResponse> requestRenewal(
            @PathVariable UUID certId,
            @RequestBody(required = false) RequestRenewalRequest req,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) {

        UUID orgId = TenantContext.getOrgId();
        if (req == null) req = new RequestRenewalRequest(null, null);

        subscriptionGuard.assertScansAllowed(orgId);

        CertificateRecord cert = certRepository.findByIdAndOrgId(certId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate not found: " + certId));

        Target target = cert.getTarget();
        if (target == null || target.getAgent() == null) {
            throw new com.certguard.exception.RenewalNotSupportedException(
                    "Automatic renewal requires an agent-managed target. " +
                    "Assign an agent to this target and re-scan before requesting renewal.");
        }

        InternalCreateRenewalRequest internalReq = new InternalCreateRenewalRequest(
                orgId,
                certId,
                target.getId(),
                target.getAgent().getId(),
                principal.getUserId(),
                req.caProvider(),
                req.targetInstallPath(),
                cert.getCommonName(),
                cert.getSubjectAltNames() != null ? cert.getSubjectAltNames() : List.of()
        );

        RenewalResponse response = renewalServiceClient.createRenewal(internalReq);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * GET /api/v1/certificates/{certId}/renewals
     * Lists all renewals for a certificate (proxied from renewal service).
     */
    @GetMapping("/certificates/{certId}/renewals")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<List<RenewalResponse>> listRenewals(@PathVariable UUID certId) {
        UUID orgId = TenantContext.getOrgId();
        return ResponseEntity.ok(renewalServiceClient.listRenewals(orgId, certId));
    }

    /**
     * GET /api/v1/renewals/{renewalId}
     * Returns a single renewal (proxied from renewal service).
     */
    @GetMapping("/renewals/{renewalId}")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<RenewalResponse> getRenewal(@PathVariable UUID renewalId) {
        UUID orgId = TenantContext.getOrgId();
        return ResponseEntity.ok(renewalServiceClient.getRenewal(orgId, renewalId));
    }

    /**
     * POST /api/v1/renewals/{renewalId}/cancel
     * Cancels an active renewal (proxied to renewal service).
     */
    @PostMapping("/renewals/{renewalId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<RenewalResponse> cancelRenewal(@PathVariable UUID renewalId) {
        UUID orgId = TenantContext.getOrgId();
        return ResponseEntity.ok(renewalServiceClient.cancelRenewal(orgId, renewalId));
    }

    /**
     * GET /api/v1/renewals/{renewalId}/package
     * Streams the certificate PEM package (proxied from renewal service).
     */
    @GetMapping("/renewals/{renewalId}/package")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public void downloadPackage(@PathVariable UUID renewalId,
                                HttpServletResponse response) throws IOException {
        UUID orgId = TenantContext.getOrgId();
        response.setContentType("application/x-pem-file");
        try (InputStream stream = renewalServiceClient.streamPackage(renewalId)) {
            stream.transferTo(response.getOutputStream());
        }
        log.info("Package download proxied for renewalId: {}, orgId: {}", renewalId, orgId);
    }

    /**
     * GET /api/v1/renewal/providers
     * Returns the list of supported CA providers from the renewal service.
     * Used by the UI to populate the provider dropdown in the renewal modal.
     */
    @GetMapping("/renewal/providers")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<List<Map<String, String>>> listProviders() {
        return ResponseEntity.ok(renewalServiceClient.listProviders());
    }
}
