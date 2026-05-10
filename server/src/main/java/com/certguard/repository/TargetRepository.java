package com.certguard.repository;

import com.certguard.dto.response.MspChildOrgStat;
import com.certguard.entity.Target;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TargetRepository extends JpaRepository<Target, UUID> {
    Page<Target> findAllByOrganizationId(UUID orgId, Pageable pageable);
    List<Target> findAllByOrganizationIdAndIsPrivateFalseAndEnabledTrue(UUID orgId);
    List<Target> findAllByIsPrivateFalseAndEnabledTrue();
    Optional<Target> findByIdAndOrganizationId(UUID id, UUID orgId);
    boolean existsByOrganizationIdAndHostAndPort(UUID orgId, String host, int port);
    long countByOrganizationId(UUID orgId);
    long countByLocationId(UUID locationId);

    // ── V22: Soft-delete helpers ──────────────────────────────────────────

    @Modifying
    @Query("UPDATE Target t SET t.enabled = false WHERE t.organization.id = :orgId")
    void disableAllForOrg(@Param("orgId") UUID orgId);

    @Modifying
    @Query("UPDATE Target t SET t.enabled = true WHERE t.organization.id = :orgId")
    void enableAllForOrg(@Param("orgId") UUID orgId);

    // ── V22: MSP aggregated views ─────────────────────────────────────────

    long countByOrganizationIdIn(Collection<UUID> orgIds);

    @Query("SELECT t FROM Target t JOIN FETCH t.organization o " +
           "WHERE o.id IN :orgIds AND o.archivedAt IS NULL")
    Page<Target> findAllByOrgIdInWithOrg(@Param("orgIds") Collection<UUID> orgIds, Pageable pageable);

    @Query("SELECT new com.certguard.dto.response.MspChildOrgStat(o.id, o.name, COUNT(t.id)) " +
           "FROM Organization o LEFT JOIN Target t ON t.organization = o " +
           "WHERE o.id IN :orgIds GROUP BY o.id, o.name")
    List<MspChildOrgStat> countTargetsAndCertsPerOrg(@Param("orgIds") Collection<UUID> orgIds);
}
