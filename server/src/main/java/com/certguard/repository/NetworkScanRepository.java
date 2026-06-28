package com.certguard.repository;

import com.certguard.entity.NetworkScan;
import com.certguard.enums.NetworkScanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NetworkScanRepository extends JpaRepository<NetworkScan, UUID> {

    Page<NetworkScan> findByOrgIdOrderByCreatedAtDesc(UUID orgId, Pageable pageable);

    Optional<NetworkScan> findByIdAndOrgId(UUID id, UUID orgId);

    List<NetworkScan> findByAgent_IdAndStatusIn(UUID agentId, List<NetworkScanStatus> statuses);
}
