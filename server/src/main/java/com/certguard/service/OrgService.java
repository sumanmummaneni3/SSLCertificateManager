package com.certguard.service;

import com.certguard.dto.request.UpdateOrgProfileRequest;
import com.certguard.dto.response.OrgResponse;
import com.certguard.entity.Organization;
import com.certguard.entity.OrgMember;
import com.certguard.entity.Subscription;
import com.certguard.entity.User;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.OrgType;
import com.certguard.enums.SubscriptionStatus;
import com.certguard.enums.UserRole;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.OrgMemberRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.SubscriptionRepository;
import com.certguard.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgService {

    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final OrgMemberRepository orgMemberRepository;

    /**
     * Dev-only: find-or-create a user+org+subscription for the given email and role.
     * Called exclusively from DevAuthController (active only when app.dev-mode=true).
     */
    @Transactional(readOnly = false)
    public User provisionDevUser(String email, UserRole userRole) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            Organization org = Organization.builder()
                    .name(email.split("@")[0] + "'s Org")
                    .build();
            orgRepository.save(org);
            int quota = (userRole == UserRole.PLATFORM_ADMIN) ? 0 : 10;
            subscriptionRepository.save(Subscription.builder()
                    .organization(org)
                    .maxCertificateQuota(quota)
                    .status(SubscriptionStatus.ACTIVE)
                    .build());
            User user = userRepository.save(User.builder()
                    .organization(org)
                    .email(email)
                    .name("Dev User")
                    .role(userRole)
                    .build());
            if (userRole != UserRole.PLATFORM_ADMIN) {
                OrgMemberRole memberRole = switch (userRole) {
                    case VIEWER -> OrgMemberRole.VIEWER;
                    case MEMBER -> OrgMemberRole.ENGINEER;
                    default     -> OrgMemberRole.ADMIN;
                };
                orgMemberRepository.save(OrgMember.builder()
                        .organization(org)
                        .user(user)
                        .role(memberRole)
                        .inviteStatus(InviteStatus.ACCEPTED)
                        .build());
            }
            return user;
        });
    }

    public OrgResponse getOrg(UUID orgId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId).orElse(null);
        return toResponse(org, sub);
    }

    @Transactional
    public OrgResponse updateProfile(UUID orgId, UpdateOrgProfileRequest req) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        if (req.getName() != null)          org.setName(req.getName());
        if (req.getAddressLine1() != null)  org.setAddressLine1(req.getAddressLine1());
        if (req.getAddressLine2() != null)  org.setAddressLine2(req.getAddressLine2());
        if (req.getCity() != null)          org.setCity(req.getCity());
        if (req.getStateProvince() != null) org.setStateProvince(req.getStateProvince());
        if (req.getPostalCode() != null)    org.setPostalCode(req.getPostalCode());
        if (req.getCountry() != null)       org.setCountry(req.getCountry());
        if (req.getPhone() != null)         org.setPhone(req.getPhone());
        if (req.getContactEmail() != null)  org.setContactEmail(req.getContactEmail());
        if (req.getIsMsp() != null) {
            boolean isPlatformAdmin = SecurityContextHolder.getContext().getAuthentication()
                    .getAuthorities().stream()
                    .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
            if (!isPlatformAdmin) {
                throw new AccessDeniedException("MSP promotion is sales-assisted. Contact support.");
            }
            org.setOrgType(req.getIsMsp() ? OrgType.MSP : OrgType.SINGLE);
        }
        orgRepository.save(org);
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId).orElse(null);
        return toResponse(org, sub);
    }

    /** Legacy — keep for backward compat; delegates to updateProfile */
    @Transactional
    public OrgResponse updateName(UUID orgId, String name) {
        UpdateOrgProfileRequest req = new UpdateOrgProfileRequest();
        req.setName(name);
        return updateProfile(orgId, req);
    }

    @Transactional
    public OrgResponse updateCertificateQuota(UUID orgId, int newQuota) {
        if (newQuota < 0) throw new IllegalArgumentException("Certificate quota must be at least 0");
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription not found for org: " + orgId));
        sub.setMaxCertificateQuota(newQuota);
        subscriptionRepository.save(sub);
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        return toResponse(org, sub);
    }

    /**
     * Self-service MSP upgrade: flips a SINGLE org to MSP immediately, with no
     * sales review. The free-tier certificate quota (10) is preserved as-is —
     * scanning beyond it requires a paid quota increase, enforced in
     * {@link TargetService}. Idempotent for orgs that are already MSP.
     */
    @Transactional
    public OrgResponse upgradeToMsp(UUID orgId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
        if (org.getOrgType() != OrgType.MSP) {
            org.setOrgType(OrgType.MSP);
            orgRepository.save(org);
        }
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId).orElse(null);
        return toResponse(org, sub);
    }

    public List<OrgResponse> listAllOrgs() {
        return orgRepository.findAll().stream()
                .map(org -> {
                    Subscription sub = subscriptionRepository.findByOrganizationId(org.getId()).orElse(null);
                    return toResponse(org, sub);
                }).toList();
    }

    OrgResponse toResponse(Organization org, Subscription sub) {
        return OrgResponse.builder()
                .id(org.getId())
                .name(org.getName())
                .slug(org.getSlug())
                .orgType(org.getOrgType())
                .parentOrgId(org.getParentOrg() != null ? org.getParentOrg().getId() : null)
                .addressLine1(org.getAddressLine1())
                .addressLine2(org.getAddressLine2())
                .city(org.getCity())
                .stateProvince(org.getStateProvince())
                .postalCode(org.getPostalCode())
                .country(org.getCountry())
                .phone(org.getPhone())
                .contactEmail(org.getContactEmail())
                .maxCertificateQuota(sub != null ? sub.getMaxCertificateQuota() : 10)
                .status(sub != null ? sub.getStatus() : null)
                .createdAt(org.getCreatedAt())
                .build();
    }
}
