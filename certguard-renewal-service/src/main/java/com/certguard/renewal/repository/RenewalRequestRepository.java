package com.certguard.renewal.repository;

import com.certguard.renewal.entity.CertificateRenewalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RenewalRequestRepository extends JpaRepository<CertificateRenewalRequest, UUID> {

    List<CertificateRenewalRequest> findByOrgIdAndCertId(UUID orgId, UUID certId);

    Optional<CertificateRenewalRequest> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<CertificateRenewalRequest> findByCsrJobId(UUID csrJobId);

    boolean existsByCertIdAndStatusIn(UUID certId, List<String> statuses);
}
