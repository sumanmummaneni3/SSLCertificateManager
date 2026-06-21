package com.certguard.controller;

import com.certguard.dto.response.MspDashboardResponse;
import com.certguard.dto.response.MspTargetRow;
import com.certguard.security.TenantContext;
import com.certguard.service.MspDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/msp", produces = "application/json")
@RequiredArgsConstructor
public class MspDashboardController {

    private final MspDashboardService mspDashboardService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<MspDashboardResponse> dashboard() {
        return ResponseEntity.ok(mspDashboardService.getDashboard(TenantContext.getOrgId()));
    }

    @GetMapping("/targets")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<Page<MspTargetRow>> targets(
            @RequestParam(required = false) UUID orgId, Pageable pageable) {
        return ResponseEntity.ok(
                mspDashboardService.listTargetsAcrossChildren(TenantContext.getOrgId(), orgId, pageable));
    }
}
