package com.certguard.controller;

import com.certguard.dto.request.CreateClientOrgRequest;
import com.certguard.dto.response.OrgResponse;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.security.TenantContext;
import com.certguard.service.MspClientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/msp", produces = "application/json")
@RequiredArgsConstructor
public class MspClientController {

    private final MspClientService mspClientService;

    @GetMapping("/clients")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<List<OrgResponse>> listClients() {
        return ResponseEntity.ok(mspClientService.listClients(TenantContext.getOrgId()));
    }

    @GetMapping("/clients/{clientOrgId}")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<OrgResponse> getClient(@PathVariable UUID clientOrgId) {
        return ResponseEntity.ok(mspClientService.getClient(TenantContext.getOrgId(), clientOrgId));
    }

    @PostMapping("/clients")
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<OrgResponse> createClient(
            @Valid @RequestBody CreateClientOrgRequest req,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mspClientService.createClient(TenantContext.getOrgId(), principal.getUserId(), req));
    }

    @PutMapping("/clients/{clientOrgId}")
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<OrgResponse> updateClient(
            @PathVariable UUID clientOrgId,
            @Valid @RequestBody CreateClientOrgRequest req) {
        return ResponseEntity.ok(mspClientService.updateClient(TenantContext.getOrgId(), clientOrgId, req));
    }
}
