package com.certguard.repository;
import com.certguard.entity.Target;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
@Repository
public interface TargetRepository extends JpaRepository<Target, UUID> {
    Page<Target> findAllByOrganizationId(UUID orgId, Pageable pageable);
    List<Target> findAllByOrganizationIdAndIsPrivateFalseAndEnabledTrue(UUID orgId);
    List<Target> findAllByIsPrivateFalseAndEnabledTrue();
    Optional<Target> findByIdAndOrganizationId(UUID id, UUID orgId);
    boolean existsByOrganizationIdAndHostAndPort(UUID orgId, String host, int port);
    long countByOrganizationId(UUID orgId);
    long countByLocationId(UUID locationId);
}
