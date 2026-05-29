package com.certguard.renewal.repository;

import com.certguard.renewal.entity.CertIssuanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CertIssuanceHistoryRepository extends JpaRepository<CertIssuanceHistory, UUID> {

    List<CertIssuanceHistory> findByOrgIdAndCertIdOrderByIssuedAtDesc(UUID orgId, UUID certId);
}
