package com.certguard.controller;

import com.certguard.dto.request.CompleteOnboardingRequest;
import com.certguard.dto.response.OrgResponse;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.security.TenantContext;
import com.certguard.service.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/onboarding", produces = "application/json")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<OrgResponse> completeOnboarding(
            @Valid @RequestBody CompleteOnboardingRequest req,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) {
        return ResponseEntity.ok(onboardingService.complete(
                principal.getUserId(), TenantContext.getOrgId(), req));
    }
}
