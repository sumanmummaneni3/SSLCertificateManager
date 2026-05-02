package com.certguard.controller;

import com.certguard.dto.IssueBundleResult;
import com.certguard.dto.request.CreateAgentRequest;
import com.certguard.dto.response.IssueBundleResponse;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.security.TenantContext;
import com.certguard.service.AgentBundleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Handles agent provisioning endpoints at /api/v1/agents.
 *
 * POST /api/v1/agents               — create agent and issue one-time installer bundle
 * GET  /api/v1/agents/{id}/bundle   — one-time bundle download (dlToken bearer)
 *
 * These were previously mapped incorrectly inside AgentController (which has
 * a class-level @RequestMapping("/api/v1/agent")) causing the effective path
 * to resolve to /api/v1/agent/api/v1/agents — a 404 for every UI call.
 */
@Slf4j
@RestController
@RequestMapping(value = "/api/v1/agents", produces = "application/json")
public class AgentProvisionController {

    private final AgentBundleService agentBundleService;

    public AgentProvisionController(AgentBundleService agentBundleService) {
        this.agentBundleService = agentBundleService;
    }

    /**
     * POST /api/v1/agents
     * Creates a new agent and returns a one-time install key + signed download URL.
     * Requires ADMIN or PLATFORM_ADMIN role.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<IssueBundleResponse> createAgentWithBundle(
            @Valid @RequestBody CreateAgentRequest request,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) throws Exception {

        IssueBundleResult result = agentBundleService.issueBundle(
                request, TenantContext.getOrgId(), principal.getUserId());

        IssueBundleResponse response = IssueBundleResponse.builder()
                .agentId(result.agentId())
                .installKey(result.installKey())
                .bundleDownloadUrl(result.bundleDownloadUrl())
                .expiresAt(result.expiresAt())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/v1/agents/{agentId}/bundle?dlToken=...
     * One-time bundle download — no JWT required; dlToken is the bearer credential.
     * Returns 410 Gone if the token has expired or was already consumed.
     */
    @GetMapping(value = "/{agentId}/bundle", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> downloadBundle(
            @PathVariable UUID agentId,
            @RequestParam @NotBlank String dlToken) throws Exception {

        byte[] zipBytes = agentBundleService.buildBundleZip(agentId, dlToken);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"certguard-agent-" + agentId + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zipBytes);
    }
}
