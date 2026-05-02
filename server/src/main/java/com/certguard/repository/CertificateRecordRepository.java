package com.certguard.repository;

import com.certguard.entity.CertificateRecord;
import com.certguard.enums.CertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CertificateRecordRepository extends JpaRepository<CertificateRecord, UUID> {

    List<CertificateRecord> findAllByTargetId(UUID targetId);

    Optional<CertificateRecord> findByTargetIdAndSerialNumber(UUID targetId, String serialNumber);

    Optional<CertificateRecord> findTopByTargetIdOrderByScannedAtDesc(UUID targetId);

    Page<CertificateRecord> findAllByOrgId(UUID orgId, Pageable pageable);

    long countByOrgIdAndStatus(UUID orgId, CertStatus status);

    @Query("SELECT c FROM CertificateRecord c WHERE c.orgId = :orgId AND c.expiryDate BETWEEN :from AND :to")
    List<CertificateRecord> findExpiringByOrgId(UUID orgId, Instant from, Instant to);

    /**
     * Cross-org fetch of all certificates expiring within [now, threshold], eagerly
     * joining their target. Replaces the per-org loop in CertificateExpiryScheduler
     * to eliminate the N+1 query pattern.
     */
    @Query("SELECT c FROM CertificateRecord c JOIN FETCH c.target t " +
           "WHERE c.expiryDate BETWEEN :now AND :threshold " +
           "AND c.status IN ('VALID', 'EXPIRING') " +
           "AND t.enabled = true")
    List<CertificateRecord> findExpiringWithTargets(
            @Param("now") Instant now,
            @Param("threshold") Instant threshold);

    /**
     * Batch-load all certificate records for a given set of target IDs, ordered
     * newest first. Used by TargetService.listTargets to avoid N+1: one query for
     * the full page, then one query here, joined in memory.
     */
    @Query("SELECT c FROM CertificateRecord c WHERE c.target.id IN :targetIds ORDER BY c.scannedAt DESC")
    List<CertificateRecord> findLatestByTargetIds(@Param("targetIds") List<UUID> targetIds);

    /**
     * Stamps last_alert_sent_at on a single certificate record.
     * Called by CertificateExpiryScheduler immediately after a successful dispatch
     * to prevent repeat alerts on the next daily run.
     */
    @Modifying
    @Query("UPDATE CertificateRecord c SET c.lastAlertSentAt = :sentAt WHERE c.id = :id")
    void stampAlertSentAt(UUID id, Instant sentAt);
}
