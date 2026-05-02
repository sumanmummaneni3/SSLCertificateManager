package com.certguard.repository;

import com.certguard.entity.AgentScanJob;
import com.certguard.enums.ScanJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentScanJobRepository extends JpaRepository<AgentScanJob, UUID> {

    Optional<AgentScanJob> findByIdAndAgentId(UUID jobId, UUID agentId);

    boolean existsByTargetIdAndStatusIn(UUID targetId, List<ScanJobStatus> statuses);

    /**
     * Atomically claims up to {@code limit} PENDING jobs for the given agent using
     * PostgreSQL's {@code FOR UPDATE SKIP LOCKED}. Prevents duplicate-claim races
     * when multiple server replicas serve the same agent concurrently.
     * Must be called within an active transaction.
     */
    @Query(value = """
        SELECT * FROM agent_scan_jobs
        WHERE agent_id = :agentId AND status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<AgentScanJob> claimPendingJobsWithLock(
            @Param("agentId") UUID agentId,
            @Param("limit") int limit);

    // Latest job for a target — used by scan-status endpoint
    @Query("SELECT j FROM AgentScanJob j WHERE j.target.id = :targetId ORDER BY j.createdAt DESC")
    List<AgentScanJob> findByTargetIdOrderByCreatedAtDesc(UUID targetId);

    // Stale CLAIMED jobs — reset to PENDING after timeout
    @Query("SELECT j FROM AgentScanJob j WHERE j.status = 'CLAIMED' AND j.claimedAt < :before")
    List<AgentScanJob> findStaleClaimedJobs(Instant before);

    // Clean up old completed/failed jobs older than retention period
    @Modifying
    @Query("DELETE FROM AgentScanJob j WHERE j.status IN ('COMPLETED','FAILED') AND j.createdAt < :before")
    int deleteOldCompletedJobs(Instant before);
}
