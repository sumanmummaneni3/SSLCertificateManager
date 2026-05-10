package com.certguard.service;

import com.certguard.dto.admin.AdminOrgDetailDto;
import com.certguard.dto.admin.AdminOrgDto;
import com.certguard.dto.admin.AdminOrgTreeDto;
import com.certguard.dto.response.OrgResponse;
import com.certguard.entity.Organization;
import com.certguard.entity.PlatformAdminAudit;
import com.certguard.entity.Subscription;
import com.certguard.enums.OrgType;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.entity.User;
import com.certguard.repository.AgentRepository;
import com.certguard.repository.OrgMemberRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.PlatformAdminAuditRepository;
import com.certguard.repository.SubscriptionRepository;
import com.certguard.repository.TargetRepository;
import com.certguard.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final TargetRepository targetRepository;
    private final AgentRepository agentRepository;
    private final PlatformAdminAuditRepository auditRepository;
    private final OrgService orgService;
    private final UserRepository userRepository;

    public AdminService(OrganizationRepository orgRepository,
                        SubscriptionRepository subscriptionRepository,
                        OrgMemberRepository orgMemberRepository,
                        TargetRepository targetRepository,
                        AgentRepository agentRepository,
                        PlatformAdminAuditRepository auditRepository,
                        OrgService orgService,
                        UserRepository userRepository) {
        this.orgRepository        = orgRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.orgMemberRepository  = orgMemberRepository;
        this.targetRepository     = targetRepository;
        this.agentRepository      = agentRepository;
        this.auditRepository      = auditRepository;
        this.orgService           = orgService;
        this.userRepository       = userRepository;
    }

    /**
     * Returns all orgs as a flat list, N+1-free.
     * Subscriptions are fetched in a single additional query and joined in Java.
     */
    public List<AdminOrgDto> listAllOrgs() {
        // Fetch all orgs (with parent resolved in one join query)
        List<Organization> orgs = orgRepository.findAllWithParent();

        // Fetch all subscriptions in one query and index by org id
        Map<UUID, Subscription> subByOrgId = subscriptionRepository.findAllWithOrganization()
                .stream()
                .collect(Collectors.toMap(s -> s.getOrganization().getId(), s -> s));

        return orgs.stream()
                .map(org -> toAdminOrgDto(org, subByOrgId.get(org.getId())))
                .toList();
    }

    /**
     * Returns orgs grouped into a tree: top-level SINGLE/MSP at root, MSP orgs have
     * nested clients[] populated from children where parentOrgId == msp.id.
     */
    public List<AdminOrgTreeDto> getOrgTree() {
        List<AdminOrgDto> flat = listAllOrgs();

        // Index flat list by id for quick lookup
        Map<UUID, AdminOrgDto> byId = flat.stream()
                .collect(Collectors.toMap(AdminOrgDto::id, d -> d));

        // Group children by parentOrgId
        Map<UUID, List<AdminOrgDto>> childrenByParent = new HashMap<>();
        for (AdminOrgDto dto : flat) {
            if (dto.parentOrgId() != null) {
                childrenByParent.computeIfAbsent(dto.parentOrgId(), k -> new ArrayList<>()).add(dto);
            }
        }

        List<AdminOrgTreeDto> result = new ArrayList<>();
        for (AdminOrgDto dto : flat) {
            if (dto.parentOrgId() != null) {
                // Not a root-level org — skip; it will appear as a client of its parent
                continue;
            }
            List<AdminOrgDto> clients = childrenByParent.getOrDefault(dto.id(), List.of());
            result.add(toTreeDto(dto, clients));
        }
        return result;
    }

    public AdminOrgDetailDto getOrgDetail(UUID orgId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId).orElse(null);
        long memberCount = orgMemberRepository.findAllByOrganizationId(orgId).size();
        long targetCount = targetRepository.countByOrganizationId(orgId);
        long agentCount  = agentRepository.findAllByOrganizationId(orgId).size();

        return new AdminOrgDetailDto(
                org.getId(),
                org.getName(),
                org.getSlug(),
                org.getOrgType(),
                org.getParentOrg() != null ? org.getParentOrg().getId() : null,
                memberCount,
                targetCount,
                agentCount,
                sub != null ? sub.getStatus().name() : null,
                sub != null ? sub.getStatus() : null,
                org.getCreatedAt(),
                org.getAddressLine1(),
                org.getAddressLine2(),
                org.getCity(),
                org.getStateProvince(),
                org.getPostalCode(),
                org.getCountry(),
                org.getPhone(),
                org.getContactEmail()
        );
    }

    public List<AdminOrgDto> listMsps() {
        return listAllOrgs().stream()
                .filter(dto -> dto.orgType() == OrgType.MSP)
                .toList();
    }

    /**
     * Delegates quota update to OrgService — single source of truth for quota logic.
     */
    @Transactional
    public OrgResponse updateQuota(UUID orgId, int newQuota) {
        return orgService.updateCertificateQuota(orgId, newQuota);
    }

    public Page<PlatformAdminAudit> listAuditEvents(UUID orgId, Instant from, Instant to,
                                                     Pageable pageable) {
        return auditRepository.findByFilters(orgId, from, to, pageable);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private AdminOrgDto toAdminOrgDto(Organization org, Subscription sub) {
        long memberCount = orgMemberRepository.findAllByOrganizationId(org.getId()).size();
        long targetCount = targetRepository.countByOrganizationId(org.getId());
        long agentCount  = agentRepository.findAllByOrganizationId(org.getId()).size();

        return new AdminOrgDto(
                org.getId(),
                org.getName(),
                org.getSlug(),
                org.getOrgType(),
                org.getParentOrg() != null ? org.getParentOrg().getId() : null,
                memberCount,
                targetCount,
                agentCount,
                sub != null ? sub.getStatus().name() : null,
                sub != null ? sub.getStatus() : null,
                org.getCreatedAt()
        );
    }

    private AdminOrgTreeDto toTreeDto(AdminOrgDto dto, List<AdminOrgDto> clients) {
        return new AdminOrgTreeDto(
                dto.id(),
                dto.name(),
                dto.slug(),
                dto.orgType(),
                dto.parentOrgId(),
                dto.memberCount(),
                dto.targetCount(),
                dto.agentCount(),
                dto.subscriptionTier(),
                dto.subscriptionStatus(),
                dto.createdAt(),
                clients
        );
    }

    // ── MSP promotion / demotion ──────────────────────────────────────────────

    @Transactional
    public OrgResponse promoteToMsp(UUID orgId, String reason) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
        if (org.getOrgType() == OrgType.MSP) {
            return orgService.toResponse(org, subscriptionRepository.findByOrganizationId(orgId).orElse(null));
        }
        org.setOrgType(OrgType.MSP);
        orgRepository.save(org);
        log.info("Org {} promoted to MSP by platform admin (reason='{}')", orgId, reason);
        return orgService.toResponse(org, subscriptionRepository.findByOrganizationId(orgId).orElse(null));
    }

    @Transactional
    public OrgResponse demoteFromMsp(UUID orgId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
        long childCount = orgRepository.countByParentOrgIdAndArchivedAtIsNull(orgId);
        if (childCount > 0) {
            throw new IllegalStateException(
                    "Cannot demote MSP with " + childCount + " active child orgs. Archive children first.");
        }
        org.setOrgType(OrgType.SINGLE);
        orgRepository.save(org);
        return orgService.toResponse(org, subscriptionRepository.findByOrganizationId(orgId).orElse(null));
    }

    // ── Soft-delete / archive ─────────────────────────────────────────────────

    @Transactional
    public void archiveOrg(UUID orgId, UUID actingUserId, String reason) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Org not found: " + orgId));
        if (org.getArchivedAt() != null) return;

        Instant now = Instant.now();
        User actor = userRepository.findById(actingUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Acting user not found: " + actingUserId));
        archiveCascade(org, actor, reason, now);
    }

    private void archiveCascade(Organization org, User actor, String reason, Instant now) {
        org.setArchivedAt(now);
        org.setArchivedBy(actor);
        org.setArchiveReason(reason);
        orgRepository.save(org);

        targetRepository.disableAllForOrg(org.getId());
        agentRepository.revokeAllForOrg(org.getId());

        auditRepository.save(PlatformAdminAudit.of(
                actor.getId(), actor.getEmail(),
                org.getId(), org.getName(),
                "DELETE", "/api/v1/admin/orgs/" + org.getId(),
                "Archive: " + (reason != null ? reason : ""),
                200));

        if (org.getOrgType() == OrgType.MSP) {
            for (Organization child : orgRepository.findAllByParentOrgIdAndArchivedAtIsNull(org.getId())) {
                archiveCascade(child, actor, "parent-msp-archived: " + reason, now);
            }
        }
    }

    @Transactional
    public OrgResponse restoreOrg(UUID orgId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Org not found"));
        org.setArchivedAt(null);
        org.setArchivedBy(null);
        org.setArchiveReason(null);
        orgRepository.save(org);
        targetRepository.enableAllForOrg(orgId);
        return orgService.getOrg(orgId);
    }
}
