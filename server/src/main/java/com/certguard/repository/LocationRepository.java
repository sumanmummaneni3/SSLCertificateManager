package com.certguard.repository;

import com.certguard.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LocationRepository extends JpaRepository<Location, UUID> {
    List<Location> findAllByOrganizationId(UUID orgId);
    Optional<Location> findByIdAndOrganizationId(UUID id, UUID orgId);
    boolean existsByOrganizationId(UUID orgId);
    long countByOrganizationId(UUID orgId);
}
