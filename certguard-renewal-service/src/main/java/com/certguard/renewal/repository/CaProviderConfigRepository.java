package com.certguard.renewal.repository;

import com.certguard.renewal.entity.CaProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CaProviderConfigRepository extends JpaRepository<CaProviderConfig, UUID> {

    List<CaProviderConfig> findByOrgId(UUID orgId);

    Optional<CaProviderConfig> findByOrgIdAndProviderType(UUID orgId, String providerType);

    Optional<CaProviderConfig> findByPlatformDefaultTrue();
}
