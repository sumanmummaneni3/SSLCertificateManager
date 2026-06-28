package com.certguard.repository;

import com.certguard.entity.AnonScanSession;
import com.certguard.enums.AnonSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AnonScanSessionRepository extends JpaRepository<AnonScanSession, UUID> {

    Optional<AnonScanSession> findByScanTokenHash(String hash);

    Optional<AnonScanSession> findByViewTokenHash(String hash);

    List<AnonScanSession> findByViewExpiresAtBeforeOrStatusIn(
            Instant cutoff, List<AnonSessionStatus> statuses);
}
