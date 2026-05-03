package com.certguard.service;

import com.certguard.dto.request.InviteMemberRequest;
import com.certguard.dto.response.InvitationResponse;
import com.certguard.dto.response.OrgMemberResponse;
import com.certguard.entity.*;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
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

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

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

        // If already an accepted member, reject
        memberRepository.findAllByOrganizationId(orgId).stream()
                .filter(m -> m.getUser().getEmail().equalsIgnoreCase(req.getEmail())
                        && m.getInviteStatus() == InviteStatus.ACCEPTED)
                .findFirst()
                .ifPresent(m -> { throw new IllegalArgumentException(
                        req.getEmail() + " is already a member of this organisation"); });

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
        String inviteLink = baseUrl + "/invite?token=" + rawToken;
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
    public void revokeMember(UUID orgId, UUID requestingUserId, UUID targetUserId) {
        if (requestingUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("You cannot revoke your own access");
        }
        OrgMember member = memberRepository.findByOrganizationIdAndUserId(orgId, targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        member.setInviteStatus(InviteStatus.REVOKED);
        memberRepository.save(member);
        log.info("Member {} revoked from org {} by {}", targetUserId, orgId, requestingUserId);
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
