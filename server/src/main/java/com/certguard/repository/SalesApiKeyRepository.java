package com.certguard.repository;

import com.certguard.entity.SalesApiKey;
import com.certguard.enums.SalesKeyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SalesApiKeyRepository extends JpaRepository<SalesApiKey, UUID> {

    Optional<SalesApiKey> findByLabel(String label);

    List<SalesApiKey> findAllByStatus(SalesKeyStatus status);

    List<SalesApiKey> findAllByOrderByCreatedAtDesc();
}
