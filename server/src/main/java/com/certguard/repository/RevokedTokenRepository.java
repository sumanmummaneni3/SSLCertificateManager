package com.certguard.repository;

import com.certguard.entity.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, UUID> {

    Optional<RevokedToken> findByUserIdAndOrgId(UUID userId, UUID orgId);

    List<RevokedToken> findAllByExpiresAtAfter(Instant threshold);

    void deleteByUserIdAndOrgId(UUID userId, UUID orgId);

    @Modifying
    @Query("DELETE FROM RevokedToken t WHERE t.expiresAt < :threshold")
    int deleteExpiredBefore(Instant threshold);
}
