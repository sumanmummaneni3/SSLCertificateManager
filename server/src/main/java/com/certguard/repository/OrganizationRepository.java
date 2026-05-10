package com.certguard.repository;

import com.certguard.entity.Organization;
import com.certguard.enums.OrgType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    List<Organization> findAllByParentOrgId(UUID parentOrgId);
    List<Organization> findAllByOrgType(OrgType orgType);
    Optional<Organization> findBySlug(String slug);

    /**
     * Fetches all organisations with their parent in a single join query.
     */
    @Query("SELECT o FROM Organization o LEFT JOIN FETCH o.parentOrg")
    List<Organization> findAllWithParent();

    // ── V22: Soft-delete + MSP scoping ────────────────────────────────────

    List<Organization> findAllByParentOrgIdAndArchivedAtIsNull(UUID parentOrgId);

    long countByParentOrgIdAndArchivedAtIsNull(UUID parentOrgId);

    @Query("SELECT o.id FROM Organization o WHERE o.parentOrg.id = :parentId AND o.archivedAt IS NULL")
    List<UUID> findActiveChildIds(@Param("parentId") UUID parentId);

    @Query("SELECT COALESCE(o.parentOrg.id, o.id) FROM Organization o WHERE o.id = :orgId")
    UUID findBillingOwner(@Param("orgId") UUID orgId);
}
