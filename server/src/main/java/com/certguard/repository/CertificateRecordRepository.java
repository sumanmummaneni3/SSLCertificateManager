package com.certguard.repository;

import com.certguard.entity.CertificateRecord;
import com.certguard.enums.CertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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
     * Stamps last_alert_sent_at on a single certificate record.
     * Called by CertificateExpiryScheduler immediately after a successful dispatch
     * to prevent repeat alerts on the next daily run.
     */
    @Modifying
    @Query("UPDATE CertificateRecord c SET c.lastAlertSentAt = :sentAt WHERE c.id = :id")
    void stampAlertSentAt(UUID id, Instant sentAt);
}
