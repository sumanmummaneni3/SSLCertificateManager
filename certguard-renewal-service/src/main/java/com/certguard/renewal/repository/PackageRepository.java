package com.certguard.renewal.repository;

import com.certguard.renewal.entity.CertificatePackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PackageRepository extends JpaRepository<CertificatePackage, UUID> {

    Optional<CertificatePackage> findByRenewalId(UUID renewalId);
}
