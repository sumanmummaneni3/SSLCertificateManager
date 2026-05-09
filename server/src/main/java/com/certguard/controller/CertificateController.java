package com.certguard.controller;

import com.certguard.dto.response.CertificateResponse;
import com.certguard.dto.response.DashboardResponse;
import com.certguard.security.TenantContext;
import com.certguard.service.CertificateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
public class CertificateController {
    private final CertificateService certificateService;

    @GetMapping("/api/v1/certificates")
    public ResponseEntity<Page<CertificateResponse>> list(Pageable pageable) {
        return ResponseEntity.ok(certificateService.listCertificates(TenantContext.getOrgId(), pageable));
    }

    @GetMapping("/api/v1/certificates/expiring")
    public ResponseEntity<List<CertificateResponse>> expiring(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(certificateService.getExpiring(TenantContext.getOrgId(), days));
    }

    @GetMapping("/api/v1/dashboard")
    public ResponseEntity<DashboardResponse> dashboard() {
        return ResponseEntity.ok(certificateService.getDashboard(TenantContext.getOrgId()));
    }
}
