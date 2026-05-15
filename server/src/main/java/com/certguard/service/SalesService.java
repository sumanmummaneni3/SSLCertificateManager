package com.certguard.service;

import com.certguard.dto.sales.SalesOrgDetailDto;
import com.certguard.dto.sales.SalesOrgSummaryDto;
import com.certguard.dto.sales.SalesSubscriptionDto;
import com.certguard.entity.Organization;
import com.certguard.entity.PlatformAdminAudit;
import com.certguard.entity.Subscription;
import com.certguard.entity.User;
import com.certguard.enums.OrgType;
import com.certguard.enums.SubscriptionStatus;
import com.certguard.enums.UserRole;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.AgentRepository;
import com.certguard.repository.OrgMemberRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.PlatformAdminAuditRepository;
import com.certguard.repository.SubscriptionRepository;
import com.certguard.repository.TargetRepository;
import com.certguard.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalesService {

    /** Sentinel email for the service account used when writing audit rows. */
    private static final String SALES_APP_EMAIL = "sales-app@certguard.internal";

    /**
     * Allowed state transitions encoded as "FROM->TO" strings.
     * Any transition not in this set is rejected with HTTP 409.
     */
    private static final Set<String> ALLOWED_TRANSITIONS = Set.of(
            "TRIAL->PENDING_ACTIVATION",
            "TRIAL->ACTIVE",
            "TRIAL->SUSPENDED",
            "TRIAL->CANCELLED",
            "PENDING_ACTIVATION->ACTIVE",
            "PENDING_ACTIVATION->SUSPENDED",
            "PENDING_ACTIVATION->CANCELLED",
            "ACTIVE->SUSPENDED",
            "ACTIVE->CANCELLED",
            "SUSPENDED->ACTIVE",
            "SUSPENDED->CANCELLED",
            "CANCELLED->ACTIVE"
    );

    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final TargetRepository targetRepository;
    private final AgentRepository agentRepository;
    private final PlatformAdminAuditRepository auditRepository;
    private final UserRepository userRepository;
    private final AdminService adminService;

    // ── Cached sentinel user UUID — set once per JVM lifetime ─────────────

    private volatile UUID salesAppUserId;

    // ── Read operations ────────────────────────────────────────────────────

    /**
     * Paginated list of organisations with their subscription summary.
     * Excludes archived orgs by default.
     * Optionally filters by subscription status and/or org type.
     */
    public Page<SalesOrgSummaryDto> listOrgs(SubscriptionStatus statusFilter,
                                              OrgType orgTypeFilter,
                                              Pageable pageable) {
        // Load all live orgs with parent in one query, then filter in Java.
        // For the Sales API the dataset is bounded and an in-memory filter is
        // simpler than a JPQL query with nullable parameters.
        List<Organization> all = orgRepository.findAllWithParent().stream()
                .filter(o -> o.getArchivedAt() == null)
                .filter(o -> orgTypeFilter == null || o.getOrgType() == orgTypeFilter)
                .collect(Collectors.toList());

        // Fetch all subscriptions in one query keyed by org id
        java.util.Map<UUID, Subscription> subByOrgId = subscriptionRepository.findAllWithOrganization()
                .stream()
                .collect(Collectors.toMap(s -> s.getOrganization().getId(), s -> s));

        List<SalesOrgSummaryDto> filtered = all.stream()
                .filter(o -> {
                    if (statusFilter == null) return true;
                    Subscription sub = subByOrgId.get(o.getId());
                    return sub != null && sub.getStatus() == statusFilter;
                })
                .map(o -> toSummaryDto(o, subByOrgId.get(o.getId())))
                .collect(Collectors.toList());

        // Manual pagination
        int start = (int) pageable.getOffset();
        int end   = Math.min(start + pageable.getPageSize(), filtered.size());
        List<SalesOrgSummaryDto> page = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);

        return new PageImpl<>(page, pageable, filtered.size());
    }

    /**
     * Full detail for a single organisation, including resource counts.
     * Throws {@link ResourceNotFoundException} if the org does not exist.
     */
    public SalesOrgDetailDto getOrgDetail(UUID orgId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId).orElse(null);

        int memberCount = orgMemberRepository.findAllByOrganizationId(orgId).size();
        int targetCount = (int) targetRepository.countByOrganizationId(orgId);
        int agentCount  = agentRepository.findAllByOrganizationId(orgId).size();

        return SalesOrgDetailDto.builder()
                .orgId(org.getId())
                .orgName(org.getName())
                .orgType(org.getOrgType())
                .subscriptionStatus(sub != null ? sub.getStatus() : null)
                .maxCertificateQuota(sub != null ? sub.getMaxCertificateQuota() : 0)
                .archivedAt(org.getArchivedAt())
                .createdAt(org.getCreatedAt())
                .targetCount(targetCount)
                .agentCount(agentCount)
                .memberCount(memberCount)
                .parentOrgId(org.getParentOrg() != null ? org.getParentOrg().getId() : null)
                .build();
    }

    // ── Write operations ───────────────────────────────────────────────────

    /**
     * Updates the subscription status for the given org.
     * Validates the transition and writes an audit row attributed to the
     * sales service account.
     *
     * @throws ResourceNotFoundException if no subscription exists for the org
     * @throws ResponseStatusException(409) on invalid transition
     */
    @Transactional(readOnly = false)
    public SalesSubscriptionDto updateSubscriptionStatus(UUID orgId,
                                                          SubscriptionStatus newStatus,
                                                          String reason,
                                                          String actingKeyLabel) {
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription not found for org: " + orgId));

        SubscriptionStatus current = sub.getStatus();
        assertValidTransition(current, newStatus);

        sub.setStatus(newStatus);
        subscriptionRepository.save(sub);

        writeAuditRow(orgId, actingKeyLabel, "SUBSCRIPTION_STATUS_CHANGE",
                current.name() + " -> " + newStatus.name() + (reason != null ? ": " + reason : ""));

        log.info("Subscription status for org {} changed from {} to {} by sales-key '{}'",
                orgId, current, newStatus, actingKeyLabel);

        return toSubscriptionDto(sub, orgId);
    }

    /**
     * Promotes an org to MSP and activates its subscription in a single transaction.
     * Called by the POST /orgs/{orgId}/activate-msp endpoint.
     */
    @Transactional(readOnly = false)
    public SalesOrgDetailDto activateMsp(UUID orgId, String actingKeyLabel) {
        adminService.promoteToMsp(orgId, "Sales activation via API");
        updateSubscriptionStatus(orgId, SubscriptionStatus.ACTIVE, "Sales activation", actingKeyLabel);
        return getOrgDetail(orgId);
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void assertValidTransition(SubscriptionStatus from, SubscriptionStatus to) {
        String key = from.name() + "->" + to.name();
        if (!ALLOWED_TRANSITIONS.contains(key)) {
            ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            pd.setTitle("Invalid subscription transition");
            pd.setDetail("Transition from " + from + " to " + to + " is not permitted");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Transition from " + from + " to " + to + " is not permitted");
        }
    }

    /**
     * Writes a {@link PlatformAdminAudit} row attributed to the sales service account.
     * The service account user is looked up (or lazily created) on first write.
     */
    private void writeAuditRow(UUID orgId, String actingKeyLabel,
                                String action, String detail) {
        UUID salesUserId = resolveSalesAppUserId();
        Organization org = orgRepository.findById(orgId).orElse(null);
        String orgName = org != null ? org.getName() : orgId.toString();

        auditRepository.save(PlatformAdminAudit.of(
                salesUserId,
                "sales-app:" + actingKeyLabel,
                orgId,
                orgName,
                "PATCH",
                "/api/internal/v1/sales/orgs/" + orgId + "/" + action.toLowerCase().replace('_', '-'),
                detail,
                200));
    }

    /**
     * Returns the UUID of the sentinel "sales-app@certguard.internal" user.
     * Creates the user on first call if it does not exist.
     */
    private UUID resolveSalesAppUserId() {
        if (salesAppUserId != null) return salesAppUserId;

        synchronized (this) {
            if (salesAppUserId != null) return salesAppUserId;

            salesAppUserId = userRepository.findByEmail(SALES_APP_EMAIL)
                    .map(User::getId)
                    .orElseGet(() -> {
                        // The sentinel user must belong to an org — use a dummy one.
                        // We look up or create a "system" org on the fly.
                        // In practice, SalesService is only called after the system is
                        // seeded, so this path runs at most once.
                        Organization systemOrg = orgRepository.findBySlug("__system__")
                                .orElseGet(() -> {
                                    Organization o = Organization.builder()
                                            .name("System")
                                            .slug("__system__")
                                            .build();
                                    return orgRepository.save(o);
                                });

                        User salesUser = User.builder()
                                .organization(systemOrg)
                                .email(SALES_APP_EMAIL)
                                .name("Sales App")
                                .role(UserRole.PLATFORM_ADMIN)
                                .build();
                        return userRepository.save(salesUser).getId();
                    });
        }
        return salesAppUserId;
    }

    // ── DTO mappers ────────────────────────────────────────────────────────

    private SalesOrgSummaryDto toSummaryDto(Organization org, Subscription sub) {
        return SalesOrgSummaryDto.builder()
                .orgId(org.getId())
                .orgName(org.getName())
                .orgType(org.getOrgType())
                .subscriptionStatus(sub != null ? sub.getStatus() : null)
                .maxCertificateQuota(sub != null ? sub.getMaxCertificateQuota() : 0)
                .archivedAt(org.getArchivedAt())
                .createdAt(org.getCreatedAt())
                .build();
    }

    private SalesSubscriptionDto toSubscriptionDto(Subscription sub, UUID orgId) {
        return SalesSubscriptionDto.builder()
                .subscriptionId(sub.getId())
                .orgId(orgId)
                .status(sub.getStatus())
                .maxCertificateQuota(sub.getMaxCertificateQuota())
                .updatedAt(sub.getUpdatedAt())
                .build();
    }
}
