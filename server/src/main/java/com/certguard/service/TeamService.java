package com.certguard.service;

import com.certguard.dto.request.InviteMemberRequest;
import com.certguard.dto.response.InvitationResponse;
import com.certguard.dto.response.OrgMemberResponse;
import com.certguard.entity.*;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.UserRole;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
import com.certguard.security.CertGuardUserPrincipal;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamService {

    private final OrgMemberRepository memberRepository;
    private final InvitationRepository invitationRepository;
    private final OrganizationRepository orgRepository;
    private final UserRepository userRepository;
    private final InvitationService invitationService;
    private final EmailDispatchService emailDispatchService;
    private final PlatformAdminAuditService platformAdminAuditService;
    private final OrgAuditService orgAuditService;
    private final TokenRevocationService tokenRevocationService;

    @Value("${app.ui-base-url:http://localhost:8080}")
    private String uiBaseUrl;

    @PostConstruct
    void logInviteLinkOrigin() {
        log.info("Invite links will use UI base URL: {} — set APP_UI_BASE_URL if this is not the public SPA origin", uiBaseUrl);
    }

    // ── List members ──────────────────────────────────────────────────────

    public List<OrgMemberResponse> listMembers(UUID orgId) {
        return memberRepository.findAllByOrganizationId(orgId)
                .stream().map(this::toResponse).toList();
    }

    // ── Invite ────────────────────────────────────────────────────────────

    @Transactional
    public InvitationResponse invite(UUID orgId, UUID invitedByUserId, InviteMemberRequest req) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        User invitedBy = userRepository.findById(invitedByUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Platform admins have system-wide access — an org_members row would be redundant
        // and creates confusing entries in the member list without adding any capability
        userRepository.findByEmail(req.getEmail().toLowerCase().trim()).ifPresent(u -> {
            if (u.getRole() == UserRole.PLATFORM_ADMIN) {
                throw new IllegalArgumentException(
                        "Platform administrators already have system-wide access and cannot be invited as org members");
            }
        });

        // If already an accepted member, reject
        memberRepository.findAllByOrganizationId(orgId).stream()
                .filter(m -> m.getUser().getEmail().equalsIgnoreCase(req.getEmail())
                        && m.getInviteStatus() == InviteStatus.ACCEPTED)
                .findFirst()
                .ifPresent(m -> { throw new IllegalArgumentException(
                        req.getEmail() + " is already a member of this organisation"); });

        // Revoke any existing pending invite for this email so the old token is immediately dead
        invitationRepository.findFirstByOrganizationIdAndEmailAndUsedAtIsNull(
                        orgId, req.getEmail().toLowerCase().trim())
                .ifPresent(existing -> {
                    existing.setUsedAt(Instant.now());
                    invitationRepository.save(existing);
                    log.info("Revoked stale pending invite {} for {} before re-invite", existing.getId(), req.getEmail());
                });

        // Generate raw token and store its hash
        String rawToken = generateSecureToken();
        String tokenHash = sha256(rawToken);

        Invitation invitation = Invitation.builder()
                .organization(org)
                .email(req.getEmail().toLowerCase().trim())
                .role(req.getRole())
                .tokenHash(tokenHash)
                .invitedBy(invitedBy)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
        invitationRepository.save(invitation);

        // Send invite email after the transaction commits so the invitation row is
        // visible to any DB read the async task might trigger.  The lambda captures
        // all needed values; no entity references are held across the boundary to
        // avoid detached-entity issues on the async thread.
        String inviteLink = uiBaseUrl + "/invite?token=" + rawToken;
        final String toEmail    = req.getEmail();
        final String orgName    = org.getName();
        final String inviterName = invitedBy.getName();
        final OrgMemberRole role = req.getRole();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invitationService.sendInviteEmail(toEmail, orgName, inviterName, inviteLink, role);
            }
        });

        log.info("Invitation created for {} to org {} (role={})", req.getEmail(), orgId, req.getRole());

        return InvitationResponse.builder()
                .id(invitation.getId())
                .email(invitation.getEmail())
                .role(invitation.getRole())
                .expiresAt(invitation.getExpiresAt())
                .createdAt(invitation.getCreatedAt())
                .token(rawToken) // returned ONCE — never stored raw
                .build();
    }

    // ── Change role ───────────────────────────────────────────────────────

    @Transactional
    public OrgMemberResponse changeRole(UUID orgId, UUID targetUserId, OrgMemberRole newRole) {
        OrgMember member = memberRepository.findByOrganizationIdAndUserId(orgId, targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        member.setRole(newRole);
        return toResponse(memberRepository.save(member));
    }

    // ── Revoke ────────────────────────────────────────────────────────────

    @Transactional
    public void revokeMember(UUID orgId, CertGuardUserPrincipal caller, UUID targetUserId, String reason) {
        UUID requestingUserId = caller.getUserId();
        boolean isPlatformAdmin = caller.isPlatformAdmin();

        // Self-removal guard
        if (requestingUserId.equals(targetUserId)) {
            throw new IllegalStateException("You cannot remove yourself — transfer admin access to another member first");
        }

        // Load target membership — 404 if not in this org
        OrgMember targetMember = memberRepository.findByOrganizationIdAndUserId(orgId, targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found in this organisation"));

        // Idempotent: already revoked
        if (targetMember.getInviteStatus() == InviteStatus.REVOKED) {
            log.info("Member {} already revoked from org {} — no-op", targetUserId, orgId);
            return;
        }

        // Role-based authorization (defense-in-depth on top of @PreAuthorize)
        if (!isPlatformAdmin) {
            // Verify caller is an accepted ADMIN in this org
            OrgMember callerMember = memberRepository.findByOrganizationIdAndUserId(orgId, requestingUserId)
                    .orElseThrow(() -> new SecurityException("You are not a member of this organisation"));
            if (callerMember.getRole() != OrgMemberRole.ADMIN
                    || callerMember.getInviteStatus() != InviteStatus.ACCEPTED) {
                throw new SecurityException("Only organisation admins can remove members");
            }
            // OrgAdmin cannot remove another ADMIN — PLATFORM_ADMIN only
            if (targetMember.getRole() == OrgMemberRole.ADMIN) {
                throw new SecurityException(
                        "Org admins cannot remove other admins — only a Platform Admin can do this");
            }
        }

        // Last-admin guard (applies when a PA removes an ADMIN too)
        if (targetMember.getRole() == OrgMemberRole.ADMIN) {
            long acceptedAdminCount = memberRepository.countByOrganizationIdAndRoleAndInviteStatus(
                    orgId, OrgMemberRole.ADMIN, InviteStatus.ACCEPTED);
            if (acceptedAdminCount <= 1) {
                throw new IllegalStateException(
                        "Cannot remove the last admin of an organisation — assign another admin first");
            }
        }

        // Revoke the membership
        Instant now = Instant.now();
        targetMember.setInviteStatus(InviteStatus.REVOKED);
        targetMember.setRevokedAt(now);
        targetMember.setRevokedByUserId(requestingUserId);
        targetMember.setRevokeReason(reason);
        memberRepository.save(targetMember);

        // Cancel any pending invitations for this email in this org
        String targetEmail = targetMember.getUser().getEmail();
        java.util.List<Invitation> pending = invitationRepository
                .findAllByOrganizationIdAndEmailIgnoreCaseAndUsedAtIsNull(orgId, targetEmail);
        if (!pending.isEmpty()) {
            pending.forEach(inv -> {
                inv.setUsedAt(now);
                inv.setCancelledAt(now);
                inv.setCancelledReason("Member removed" + (reason != null ? ": " + reason : ""));
            });
            invitationRepository.saveAll(pending);
            log.info("Cancelled {} pending invite(s) for {} in org {} due to member removal",
                    pending.size(), targetEmail, orgId);
        }

        // Load org for audit and email
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation not found"));

        // Revoke in-flight JWTs (backed by Caffeine + DB)
        tokenRevocationService.revokeForUserInOrg(targetUserId, orgId, requestingUserId, reason);

        // Capture primitive/string values before leaving transactional context
        final String finalEmail    = targetEmail;
        final String finalTargetId = targetUserId.toString();
        final String orgName       = org.getName();
        final String callerEmail   = caller.getEmail();

        // After commit: email notification + org audit + PA audit if applicable
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emailDispatchService.sendMemberRemovedEmail(finalEmail, orgName, callerEmail);
                orgAuditService.recordAsync(orgId, requestingUserId, callerEmail,
                        "MEMBER_REMOVED", targetUserId, finalEmail, reason);
                if (isPlatformAdmin) {
                    platformAdminAuditService.recordAsync(
                            requestingUserId, callerEmail,
                            orgId, orgName,
                            "DELETE", "/api/v1/org/members/" + finalTargetId,
                            reason, 204);
                }
            }
        });

        log.info("Member {} ({}) removed from org {} by {} (platformAdmin={}, reason='{}')",
                targetUserId, targetEmail, orgId, requestingUserId, isPlatformAdmin, reason);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private OrgMemberResponse toResponse(OrgMember m) {
        return OrgMemberResponse.builder()
                .id(m.getId())
                .userId(m.getUser().getId())
                .email(m.getUser().getEmail())
                .name(m.getUser().getName())
                .role(m.getRole())
                .inviteStatus(m.getInviteStatus())
                .invitedByEmail(m.getInvitedBy() != null ? m.getInvitedBy().getEmail() : null)
                .createdAt(m.getCreatedAt())
                .build();
    }
}
