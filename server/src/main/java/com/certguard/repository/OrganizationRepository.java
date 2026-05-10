package com.certguard.repository;

import com.certguard.entity.Organization;
import com.certguard.enums.OrgType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    List<Organization> findAllByParentOrgId(UUID parentOrgId);
    List<Organization> findAllByOrgType(OrgType orgType);
    java.util.Optional<Organization> findBySlug(String slug);

    /**
     * Fetches all organisations with their subscription in a single join query,
     * eliminating the N+1 that the old OrgService.listAllOrgs() had.
     * LEFT JOIN OUTER handles orgs that have no subscription row yet.
     */
    @Query("SELECT o FROM Organization o LEFT JOIN FETCH o.parentOrg")
    List<Organization> findAllWithParent();
}
