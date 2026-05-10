package com.certguard.security;

import com.certguard.repository.OrganizationRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("mspAccessGuard")
public class MspAccessGuard {

    private final OrganizationRepository orgRepository;

    public MspAccessGuard(OrganizationRepository orgRepository) {
        this.orgRepository = orgRepository;
    }

    public boolean canAccessOrg(UUID targetOrgId) {
        UUID home = TenantContext.getOrgId();
        if (home == null) return false;
        if (home.equals(targetOrgId)) return true;
        return orgRepository.findById(targetOrgId)
                .filter(o -> o.getArchivedAt() == null)
                .map(o -> o.getParentOrg() != null && home.equals(o.getParentOrg().getId()))
                .orElse(false);
    }
}
