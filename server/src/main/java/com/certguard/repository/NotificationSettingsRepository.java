package com.certguard.repository;

import com.certguard.entity.NotificationSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, UUID> {

    /** Per-target override (at most one, enforced by partial unique index). */
    Optional<NotificationSettings> findByTargetId(UUID targetId);

    /** Org-level default (at most one per org, enforced by partial unique index). */
    Optional<NotificationSettings> findByOrganizationIdAndTargetIsNull(UUID orgId);

    // ── Batch forms for sweep N+1 mitigation (RFC §3.3) ──────────────────────

    /** Bulk-load per-target overrides for a set of target IDs (one query, no N+1). */
    List<NotificationSettings> findByTargetIdIn(Collection<UUID> targetIds);

    /** Bulk-load org defaults for a set of org IDs (one query, no N+1). */
    @Query("SELECT ns FROM NotificationSettings ns WHERE ns.organization.id IN :orgIds AND ns.target IS NULL")
    List<NotificationSettings> findByOrgIdInAndTargetIsNull(@Param("orgIds") Collection<UUID> orgIds);

    /** Returns the highest warning_days value across all rows, or null if no rows exist. */
    @Query("SELECT MAX(ns.warningDays) FROM NotificationSettings ns")
    Optional<Integer> findMaxWarningDays();
}
