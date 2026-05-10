package com.certguard.controller;

import com.certguard.enums.OrgType;
import com.certguard.repository.OrgMemberRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.UserRepository;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping(value = "/api/v1/auth/me", produces = "application/json")
@RequiredArgsConstructor
public class MeController {

    private final OrgMemberRepository orgMemberRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal CertGuardUserPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();

        boolean isPlatformAdmin = principal.isPlatformAdmin();
        String orgRole = principal.getOrgRole();
        UUID effectiveOrgId = TenantContext.getOrgId();

        // Current org info
        Map<String, Object> currentOrg = null;
        if (effectiveOrgId != null) {
            var org = organizationRepository.findById(effectiveOrgId).orElse(null);
            if (org != null) {
                currentOrg = new LinkedHashMap<>();
                currentOrg.put("id", org.getId());
                currentOrg.put("name", org.getName());
                currentOrg.put("orgType", org.getOrgType());
                currentOrg.put("role", orgRole);
            }
        }

        // All memberships for this user
        List<Map<String, Object>> memberships = orgMemberRepository
                .findAllByUserId(principal.getUserId())
                .stream()
                .map(m -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("orgId", m.getOrganization().getId());
                    entry.put("orgName", m.getOrganization().getName());
                    entry.put("role", m.getRole().name());
                    return entry;
                })
                .collect(Collectors.toList());

        // Derive permission booleans from role
        boolean isAdmin    = isPlatformAdmin || "ADMIN".equals(orgRole);
        boolean isEngineer = isAdmin || "ENGINEER".equals(orgRole);
        boolean canRead    = isEngineer || "VIEWER".equals(orgRole);

        // Onboarding state
        var dbUser = userRepository.findById(principal.getUserId()).orElse(null);
        boolean onboardingCompleted = dbUser != null && dbUser.getOnboardingCompletedAt() != null;

        boolean isMspMember = currentOrg != null && OrgType.MSP.equals(currentOrg.get("orgType"));

        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("canManageTeam",      isAdmin);
        permissions.put("canWriteTargets",    isEngineer);
        permissions.put("canWriteAgents",     isEngineer);
        permissions.put("canWriteLocations",  isEngineer);
        permissions.put("canManageMspClients", isAdmin);
        permissions.put("canEditOrgProfile",  isAdmin);
        permissions.put("canViewAllOrgs",     isPlatformAdmin);
        permissions.put("canActAsOrg",        isPlatformAdmin);
        permissions.put("isMspMember",        isMspMember);
        permissions.put("canAccessBilling",   isAdmin);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", principal.getUserId());
        user.put("email", principal.getEmail());
        user.put("onboardingCompleted",   onboardingCompleted);
        user.put("onboardingCompletedAt", dbUser != null ? dbUser.getOnboardingCompletedAt() : null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user);
        body.put("platformAdmin", isPlatformAdmin);
        body.put("currentOrg", currentOrg);
        body.put("memberships", memberships);
        body.put("permissions", permissions);

        return ResponseEntity.ok(body);
    }
}
