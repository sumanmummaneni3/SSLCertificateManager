package com.certguard.repository;

import com.certguard.entity.PlatformAdminAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface PlatformAdminAuditRepository extends JpaRepository<PlatformAdminAudit, UUID> {

    Page<PlatformAdminAudit> findByTargetOrgId(UUID orgId, Pageable pageable);

    @Query("""
            SELECT a FROM PlatformAdminAudit a
            WHERE (:orgId IS NULL OR a.targetOrgId = :orgId)
              AND (:from IS NULL OR a.createdAt >= :from)
              AND (:to   IS NULL OR a.createdAt <= :to)
            """)
    Page<PlatformAdminAudit> findByFilters(
            @Param("orgId") UUID orgId,
            @Param("from")  Instant from,
            @Param("to")    Instant to,
            Pageable pageable);
}
