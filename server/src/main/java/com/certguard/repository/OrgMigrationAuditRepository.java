package com.certguard.repository;

import com.certguard.entity.OrgMigrationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrgMigrationAuditRepository extends JpaRepository<OrgMigrationAudit, UUID> {

    /**
     * Returns the most recent FORWARD migration record for the given org, if any.
     * Used by the undo operation to locate the record to reverse.
     */
    @Query("""
            SELECT a FROM OrgMigrationAudit a
            WHERE a.orgId = :orgId AND a.direction = 'FORWARD'
            ORDER BY a.createdAt DESC
            """)
    Optional<OrgMigrationAudit> findLatestForwardByOrgId(@Param("orgId") UUID orgId);
}
