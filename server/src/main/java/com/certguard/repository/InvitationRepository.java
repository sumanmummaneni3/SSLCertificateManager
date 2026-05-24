package com.certguard.repository;

import com.certguard.entity.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    Optional<Invitation> findByTokenHash(String tokenHash);
    List<Invitation> findAllByOrganizationId(UUID orgId);
    boolean existsByOrganizationIdAndEmailAndUsedAtIsNull(UUID orgId, String email);
    Optional<Invitation> findFirstByOrganizationIdAndEmailAndUsedAtIsNull(UUID orgId, String email);
    List<Invitation> findAllByOrganizationIdAndEmailIgnoreCaseAndUsedAtIsNull(UUID orgId, String email);
    void deleteByExpiresAtBeforeAndUsedAtIsNull(Instant before);
}
