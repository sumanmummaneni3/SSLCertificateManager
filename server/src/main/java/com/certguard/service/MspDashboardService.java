package com.certguard.service;

import com.certguard.dto.response.MspChildOrgStat;
import com.certguard.dto.response.MspDashboardResponse;
import com.certguard.dto.response.MspTargetRow;
import com.certguard.entity.Organization;
import com.certguard.enums.CertStatus;
import com.certguard.enums.OrgType;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.AgentRepository;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.TargetRepository;
import com.certguard.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MspDashboardService {

    private final OrganizationRepository orgRepository;
    private final TargetRepository targetRepository;
    private final CertificateRecordRepository certRepository;
    private final AgentRepository agentRepository;

    public MspDashboardResponse getDashboard(UUID mspOrgId) {
        assertMsp(mspOrgId);
        List<UUID> orgIds = collectScope(mspOrgId);

        long totalTargets  = targetRepository.countByOrganizationIdIn(orgIds);
        long valid         = certRepository.countByOrgIdInAndStatus(orgIds, CertStatus.VALID);
        long expiring      = certRepository.countByOrgIdInAndStatus(orgIds, CertStatus.EXPIRING);
        long expired       = certRepository.countByOrgIdInAndStatus(orgIds, CertStatus.EXPIRED);
        long unreachable   = certRepository.countByOrgIdInAndStatus(orgIds, CertStatus.UNREACHABLE);
        long agentCount    = agentRepository.countByOrganizationIdIn(orgIds);
        long childOrgCount = (long) orgIds.size() - 1;

        List<MspChildOrgStat> perOrg = targetRepository.countTargetsAndCertsPerOrg(orgIds);

        return MspDashboardResponse.builder()
                .mspOrgId(mspOrgId)
                .childOrgCount(childOrgCount)
                .totalTargets(totalTargets)
                .totalAgents(agentCount)
                .valid(valid)
                .expiring(expiring)
                .expired(expired)
                .unreachable(unreachable)
                .perOrg(perOrg)
                .build();
    }

    public Page<MspTargetRow> listTargetsAcrossChildren(UUID mspOrgId, UUID filterOrgId, Pageable pageable) {
        assertMsp(mspOrgId);
        List<UUID> orgIds = collectScope(mspOrgId);
        if (filterOrgId != null) {
            if (!orgIds.contains(filterOrgId)) {
                throw new AccessDeniedException("Organisation is not within this MSP's scope");
            }
            orgIds = List.of(filterOrgId);
        }
        return targetRepository.findAllByOrgIdInWithOrg(orgIds, pageable)
                .map(MspTargetRow::fromEntity);
    }

    private void assertMsp(UUID orgId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Org not found"));
        if (org.getOrgType() != OrgType.MSP) {
            throw new AccessDeniedException("Endpoint is MSP-only");
        }
    }

    private List<UUID> collectScope(UUID mspOrgId) {
        List<UUID> scoped = TenantContext.getAccessibleOrgIds();
        if (scoped.contains(mspOrgId)) return scoped;
        List<UUID> ids = new ArrayList<>(orgRepository.findActiveChildIds(mspOrgId));
        ids.add(mspOrgId);
        return ids;
    }
}
