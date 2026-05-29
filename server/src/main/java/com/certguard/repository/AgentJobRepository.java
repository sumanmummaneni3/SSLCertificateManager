package com.certguard.repository;

import com.certguard.entity.AgentJob;
import com.certguard.enums.AgentJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentJobRepository extends JpaRepository<AgentJob, UUID> {

    boolean existsByDedupKeyAndStatusIn(String dedupKey, List<AgentJobStatus> statuses);

    Optional<AgentJob> findFirstByDedupKeyAndStatusIn(String dedupKey, List<AgentJobStatus> statuses);

    /**
     * Atomically claims up to {@code limit} PENDING CERT_DELIVERY jobs for the given agent
     * using PostgreSQL's {@code FOR UPDATE SKIP LOCKED}. Prevents duplicate-claim races.
     * Must be called within an active transaction.
     */
    @Query(value = """
        SELECT * FROM agent_jobs
        WHERE agent_id = :agentId AND status = 'PENDING' AND job_type = 'CERT_DELIVERY'
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<AgentJob> claimPendingDeliveryJobsWithLock(
            @Param("agentId") UUID agentId,
            @Param("limit") int limit);

    /**
     * Atomically claims up to {@code limit} PENDING CERT_RENEW_CSR jobs for the given agent
     * using PostgreSQL's {@code FOR UPDATE SKIP LOCKED}.
     */
    @Query(value = """
        SELECT * FROM agent_jobs
        WHERE agent_id = :agentId AND status = 'PENDING' AND job_type = 'CERT_RENEW_CSR'
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<AgentJob> claimPendingCsrJobsWithLock(
            @Param("agentId") UUID agentId,
            @Param("limit") int limit);

    /**
     * Stale CLAIMED jobs — reset to PENDING after timeout.
     */
    @Query("SELECT j FROM AgentJob j WHERE j.status = 'CLAIMED' AND j.claimedAt < :before")
    List<AgentJob> findStaleClaimedJobs(@Param("before") Instant before);

    Optional<AgentJob> findByIdAndAgentId(UUID jobId, UUID agentId);
}
