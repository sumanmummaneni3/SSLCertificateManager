package com.certguard.controller;

import com.certguard.dto.sales.CreateSalesKeyRequest;
import com.certguard.dto.sales.SalesKeyResponse;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.service.SalesApiKeyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * PLATFORM_ADMIN–only REST surface for managing Sales API keys.
 * Keys generated here are used by the external Sales app to authenticate
 * against the internal sales integration endpoints.
 */
@RestController
@RequestMapping(value = "/api/v1/admin/sales-keys", produces = "application/json")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class SalesApiKeyController {

    private final SalesApiKeyService salesApiKeyService;

    public SalesApiKeyController(SalesApiKeyService salesApiKeyService) {
        this.salesApiKeyService = salesApiKeyService;
    }

    /**
     * Create a new sales API key.
     * The {@code plainKey} in the response is only populated here — it cannot be
     * retrieved again. The caller must store it securely.
     */
    @PostMapping
    public ResponseEntity<SalesKeyResponse> createKey(
            @Valid @RequestBody CreateSalesKeyRequest req,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) {
        UUID createdByUserId = principal != null ? principal.getUserId() : null;
        SalesKeyResponse response = salesApiKeyService.createKey(req, createdByUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * List all sales API keys. The {@code plainKey} field is always null here.
     */
    @GetMapping
    public ResponseEntity<List<SalesKeyResponse>> listKeys() {
        return ResponseEntity.ok(salesApiKeyService.listKeys());
    }

    /**
     * Revoke a sales API key by id.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revokeKey(@PathVariable UUID id) {
        salesApiKeyService.revokeKey(id);
        return ResponseEntity.noContent().build();
    }
}
