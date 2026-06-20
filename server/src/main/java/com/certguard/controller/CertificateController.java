package com.certguard.controller;

import com.certguard.dto.request.RevocationDeepCheckRequest;
import com.certguard.dto.response.CertificateResponse;
import com.certguard.dto.response.DashboardResponse;
import com.certguard.dto.response.RevocationDeepCheckResponse;
import com.certguard.security.TenantContext;
import com.certguard.service.CertificateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;


@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
public class CertificateController {
    private final CertificateService certificateService;

    @GetMapping("/api/v1/certificates")
    public ResponseEntity<Page<CertificateResponse>> list(Pageable pageable) {
        return ResponseEntity.ok(certificateService.listCertificates(TenantContext.getOrgId(), pageable));
    }

    @GetMapping("/api/v1/certificates/{certId}")
    public ResponseEntity<CertificateResponse> get(@PathVariable UUID certId) {
        return ResponseEntity.ok(certificateService.getCertificate(TenantContext.getOrgId(), certId));
    }

    @GetMapping("/api/v1/certificates/expiring")
    public ResponseEntity<List<CertificateResponse>> expiring(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(certificateService.getExpiring(TenantContext.getOrgId(), days));
    }

    @GetMapping("/api/v1/dashboard")
    public ResponseEntity<DashboardResponse> dashboard() {
        return ResponseEntity.ok(certificateService.getDashboard(TenantContext.getOrgId()));
    }

    /**
     * RFC 0009 §10.2 / BE-12 — per-cert deep-check toggle.
     *
     * <p>ENGINEER+ required (mirrors scan-trigger auth on TargetController).
     *
     * <p>The {@code orgId} in the path is authoritative — it is the owning org of the cert,
     * which the UI sources from {@code CertificateResponse.orgId}. This correctly handles
     * MSP/impersonation: when an MSP engineer acts on a client org's cert, the path carries
     * the client org's UUID, not the MSP org's UUID. The repository query filters by
     * {@code (certId, orgId)}, so an org mismatch yields a 404 ProblemDetail — no data leak.
     *
     * <p>Spring Security enforces that the caller has been granted access to this org before
     * this handler is reached; no additional TenantContext check is needed here.
     */
    @PatchMapping("/api/v1/organizations/{orgId}/certificates/{certId}/revocation-deep-check")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<RevocationDeepCheckResponse> patchRevocationDeepCheck(
            @PathVariable UUID orgId,
            @PathVariable UUID certId,
            @Valid @RequestBody RevocationDeepCheckRequest req) {
        return ResponseEntity.ok(certificateService.updateRevocationDeepCheck(orgId, certId, req));
    }
}
