package com.certguard.service;

import com.certguard.entity.OrgMember;
import com.certguard.entity.User;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.repository.OrgMemberRepository;
import com.certguard.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * RFC 0015 Phase 1: resolves which org a user should be authenticated against.
 *
 * {@code User.organization} is a fixed home-org FK, but a user can hold
 * ACCEPTED memberships in other orgs (via invite-accept). Both JWT-issuance
 * paths (server-local {@code OAuth2AuthenticationSuccessHandler} and the
 * auth-service's RS256 {@code AuthProvisioningService}) must agree on the
 * same resolution order so a login never "snaps back" to the home org after
 * the user last worked in an invited org.
 *
 * Resolution order:
 *   1. {@code user.lastActiveOrgId}, if it still points at a valid
 *      (ACCEPTED, non-revoked) membership.
 *   2. The user's home org ({@code user.organization}), if valid.
 *   3. The user's most-recently-created valid membership across all orgs.
 *   4. No valid membership anywhere — home org with the least-privilege role.
 *      Never defaults to ADMIN (see §6.4 privilege fix).
 *
 * The resolved org is written back to {@code user.lastActiveOrgId} ("sticky")
 * when it changes, so the next login short-circuits at step 1.
 */
@Service
@Transactional(readOnly = true)
public class ActiveOrgResolver {

    /** Least-privilege role used when the user has no resolvable membership anywhere. */
    private static final OrgMemberRole NO_MEMBERSHIP_ROLE = OrgMemberRole.VIEWER;

    private final OrgMemberRepository orgMemberRepository;
    private final UserRepository userRepository;

    public ActiveOrgResolver(OrgMemberRepository orgMemberRepository, UserRepository userRepository) {
        this.orgMemberRepository = orgMemberRepository;
        this.userRepository = userRepository;
    }

    public record ActiveOrgContext(UUID orgId, String orgRole) {}

    @Transactional
    public ActiveOrgContext resolve(User user) {
        UUID homeOrgId = user.getOrganization().getId();
        UUID lastActiveOrgId = user.getLastActiveOrgId();

        ActiveOrgContext resolved;

        if (lastActiveOrgId != null) {
            Optional<OrgMember> lastActiveMembership = validMembership(lastActiveOrgId, user.getId());
            if (lastActiveMembership.isPresent()) {
                resolved = new ActiveOrgContext(lastActiveOrgId, lastActiveMembership.get().getRole().name());
                return stickyWriteBackIfNeeded(user, resolved);
            }
        }

        Optional<OrgMember> homeMembership = validMembership(homeOrgId, user.getId());
        if (homeMembership.isPresent()) {
            resolved = new ActiveOrgContext(homeOrgId, homeMembership.get().getRole().name());
            return stickyWriteBackIfNeeded(user, resolved);
        }

        Optional<OrgMember> mostRecent = orgMemberRepository
                .findFirstByUserIdAndInviteStatusAndRevokedAtIsNullOrderByCreatedAtDesc(
                        user.getId(), InviteStatus.ACCEPTED);
        if (mostRecent.isPresent()) {
            OrgMember member = mostRecent.get();
            resolved = new ActiveOrgContext(member.getOrganization().getId(), member.getRole().name());
            return stickyWriteBackIfNeeded(user, resolved);
        }

        // No valid membership anywhere — never default to ADMIN.
        resolved = new ActiveOrgContext(homeOrgId, NO_MEMBERSHIP_ROLE.name());
        return stickyWriteBackIfNeeded(user, resolved);
    }

    private Optional<OrgMember> validMembership(UUID orgId, UUID userId) {
        return orgMemberRepository.findByOrganizationIdAndUserIdAndInviteStatusAndRevokedAtIsNull(
                orgId, userId, InviteStatus.ACCEPTED);
    }

    /**
     * RFC 0015 Phase 2 — explicit user-driven switch. Unlike {@link #resolve}, this never
     * falls back to another org: the caller named {@code requestedOrgId} explicitly, so an
     * invalid membership is a hard failure, not a resolution step. Reuses the exact same
     * membership-validation query as {@link #resolve}'s step 1 so switch-eligibility is
     * always identical to what a future login would accept as "sticky".
     *
     * @throws SecurityException if the user has no valid (ACCEPTED, non-revoked) membership
     *         in {@code requestedOrgId}. Mapped to 403 by {@code GlobalExceptionHandler}.
     */
    @Transactional
    public ActiveOrgContext switchTo(User user, UUID requestedOrgId) {
        OrgMember membership = validMembership(requestedOrgId, user.getId())
                .orElseThrow(() -> new SecurityException(
                        "Not an active member of that organization"));

        ActiveOrgContext resolved = new ActiveOrgContext(requestedOrgId, membership.getRole().name());
        user.setLastActiveOrgId(requestedOrgId);
        userRepository.save(user);
        return resolved;
    }

    private ActiveOrgContext stickyWriteBackIfNeeded(User user, ActiveOrgContext resolved) {
        if (!resolved.orgId().equals(user.getLastActiveOrgId())) {
            user.setLastActiveOrgId(resolved.orgId());
            userRepository.save(user);
        }
        return resolved;
    }
}
