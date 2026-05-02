package com.certguard.repository;
import com.certguard.entity.AgentRegistrationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
@Repository
public interface AgentRegistrationTokenRepository extends JpaRepository<AgentRegistrationToken, UUID> {
    List<AgentRegistrationToken> findAllByOrganizationId(UUID orgId);
    @Modifying
    @Query("DELETE FROM AgentRegistrationToken t WHERE t.expiresAt < :now OR t.used = true")
    void deleteExpiredAndUsed(Instant now);

    @Query("SELECT t FROM AgentRegistrationToken t WHERE t.expiresAt < :now OR t.used = true")
    List<AgentRegistrationToken> findExpiredAndUsed(Instant now);
}
