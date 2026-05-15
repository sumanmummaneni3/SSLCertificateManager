package com.certguard.auth.repository;

import com.certguard.auth.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    Optional<UserSession> findBySessionToken(String sessionToken);

    @Modifying
    @Transactional
    int deleteBySessionToken(String sessionToken);

    @Modifying
    @Transactional
    @Query("DELETE FROM UserSession s WHERE s.expiresAt < :now")
    int deleteExpiredSessions(Instant now);

    @Modifying
    @Transactional
    @Query("DELETE FROM UserSession s WHERE s.user.id = :userId")
    int deleteAllByUserId(UUID userId);
}
