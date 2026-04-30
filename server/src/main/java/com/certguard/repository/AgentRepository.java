package com.certguard.repository;

import com.certguard.entity.Agent;
import com.certguard.enums.AgentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {

    List<Agent> findAllByOrganizationId(UUID orgId);

    Optional<Agent> findByIdAndOrganizationId(UUID id, UUID orgId);

    /**
     * Returns ACTIVE agents whose lastSeenAt is older than the given threshold —
     * i.e. agents that have gone silent and should be considered offline.
     */
    @Query("SELECT a FROM Agent a WHERE a.status = :status AND a.lastSeenAt < :threshold")
    List<Agent> findOfflineAgents(AgentStatus status, Instant threshold);
}
