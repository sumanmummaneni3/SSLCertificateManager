package com.certguard.renewal.web;

import com.certguard.renewal.entity.CertificatePackage;
import com.certguard.renewal.entity.CertificateRenewalRequest;
import com.certguard.renewal.exception.RenewalException;
import com.certguard.renewal.orchestration.RenewalOrchestrator;
import com.certguard.renewal.repository.PackageRepository;
import com.certguard.renewal.repository.RenewalRequestRepository;
import com.certguard.renewal.storage.PackageStore;
import com.certguard.renewal.ca.CaProviderRegistry;
import com.certguard.renewal.web.dto.CreateRenewalRequest;
import com.certguard.renewal.web.dto.CsrSubmitRequest;
import com.certguard.renewal.web.dto.DeliveryEventRequest;
import com.certguard.renewal.web.dto.RenewalResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal REST API consumed by certguard core server.
 * Secured via InternalServiceAuthFilter (Bearer token).
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL_SERVICE')")
public class InternalRenewalController {

    private final RenewalOrchestrator orchestrator;
    private final RenewalRequestRepository renewalRepository;
    private final PackageRepository packageRepository;
    private final PackageStore packageStore;
    private final CaProviderRegistry caProviderRegistry;

    @PostMapping("/renewals")
    public ResponseEntity<RenewalResponse> createRenewal(@RequestBody CreateRenewalRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orchestrator.createRenewal(req));
    }

    @GetMapping("/renewals/{renewalId}")
    public ResponseEntity<RenewalResponse> getRenewal(
            @PathVariable UUID renewalId,
            @RequestParam UUID orgId) {

        CertificateRenewalRequest renewal = renewalRepository.findByIdAndOrgId(renewalId, orgId)
                .orElseThrow(() -> new RenewalException("Renewal not found: " + renewalId));
        return ResponseEntity.ok(RenewalResponse.from(renewal));
    }

    @GetMapping("/renewals")
    public ResponseEntity<List<RenewalResponse>> listRenewals(
            @RequestParam UUID orgId,
            @RequestParam UUID certId) {

        List<RenewalResponse> list = renewalRepository.findByOrgIdAndCertId(orgId, certId)
                .stream().map(RenewalResponse::from).toList();
        return ResponseEntity.ok(list);
    }

    @PostMapping("/renewals/{renewalId}/cancel")
    public ResponseEntity<RenewalResponse> cancelRenewal(
            @PathVariable UUID renewalId,
            @RequestParam UUID orgId) {

        return ResponseEntity.ok(orchestrator.cancelRenewal(orgId, renewalId));
    }

    @PostMapping("/renewals/{renewalId}/csr")
    public ResponseEntity<Void> submitCsr(
            @PathVariable UUID renewalId,
            @RequestBody CsrSubmitRequest req) {

        orchestrator.submitCsr(renewalId, req.csrPem());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/renewals/{renewalId}/package")
    public void streamPackage(
            @PathVariable UUID renewalId,
            HttpServletResponse response) throws IOException {

        CertificatePackage pkg = packageRepository.findByRenewalId(renewalId)
                .orElseThrow(() -> new RenewalException("No package for renewal: " + renewalId));

        response.setContentType("application/x-pem-file");
        response.setHeader("X-Checksum-SHA256", pkg.getChecksumSha256());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + pkg.getFileName() + "\"");
        response.setContentLengthLong(pkg.getSizeBytes());

        try (InputStream stream = packageStore.openStream(pkg)) {
            stream.transferTo(response.getOutputStream());
        }
    }

    @PostMapping("/renewals/{renewalId}/delivery-completed")
    public ResponseEntity<Void> deliveryCompleted(@PathVariable UUID renewalId) {
        orchestrator.onDeliveryCompleted(renewalId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/renewals/{renewalId}/delivery-failed")
    public ResponseEntity<Void> deliveryFailed(
            @PathVariable UUID renewalId,
            @RequestBody DeliveryEventRequest req) {

        orchestrator.onDeliveryFailed(renewalId, req.errorDetail());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/providers")
    public ResponseEntity<List<Map<String, String>>> listProviders() {
        return ResponseEntity.ok(caProviderRegistry.listProviderOptions());
    }
}
