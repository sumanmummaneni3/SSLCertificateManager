package com.certguard.controller;

import com.certguard.dto.request.InviteMemberRequest;
import com.certguard.dto.response.InvitationResponse;
import com.certguard.dto.response.OrgMemberResponse;
import com.certguard.enums.OrgMemberRole;
import com.certguard.security.CertGuardUserPrincipal;
import com.certguard.security.TenantContext;
import com.certguard.service.TeamService;
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
@RequestMapping(value = "/api/v1/org", produces = "application/json")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @GetMapping("/members")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<List<OrgMemberResponse>> listMembers() {
        return ResponseEntity.ok(teamService.listMembers(TenantContext.getOrgId()));
    }

    @PostMapping("/invitations")
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<InvitationResponse> invite(
            @Valid @RequestBody InviteMemberRequest req,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.invite(TenantContext.getOrgId(), principal.getUserId(), req));
    }

    @PutMapping("/members/{userId}/role")
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<OrgMemberResponse> changeRole(
            @PathVariable UUID userId,
            @RequestParam OrgMemberRole role) {
        return ResponseEntity.ok(teamService.changeRole(TenantContext.getOrgId(), userId, role));
    }

    @DeleteMapping("/members/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID userId,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) {
        teamService.revokeMember(TenantContext.getOrgId(), principal.getUserId(), userId);
        return ResponseEntity.noContent().build();
    }
}
