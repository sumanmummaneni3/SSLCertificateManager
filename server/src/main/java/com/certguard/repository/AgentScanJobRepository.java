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
     * Atomically claims up to {@code limit} PENDING AGENT_PINNED jobs for the given agent
     * using PostgreSQL's {@code FOR UPDATE SKIP LOCKED}. Prevents duplicate-claim races
     * when multiple server replicas serve the same agent concurrently.
     * Must be called within an active transaction.
     */
    @Query(value = """
        SELECT * FROM agent_scan_jobs
        WHERE agent_id = :agentId AND status = 'PENDING' AND job_kind = 'AGENT_PINNED'
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<AgentScanJob> claimPendingJobsWithLock(
            @Param("agentId") UUID agentId,
            @Param("limit") int limit);

    /**
     * Atomically claims up to {@code batch} PENDING PUBLIC_POOL jobs for a platform scanner.
     *
     * <p>No agent_id filter — pool jobs have no pre-assigned agent. The caller stamps
     * agent_id after claiming. Uses FOR UPDATE SKIP LOCKED to prevent duplicate claims
     * when multiple scanner replicas poll concurrently.
     *
     * <p>FIFO ordering (created_at ASC) for v1 — no per-org fair-share yet (RFC 0013 §3,
     * open question 3: revisit with ORDER BY on org job counts if starvation observed).
     *
     * Must be called within an active transaction. RFC 0013 §3.
     */
    @Query(value = """
        SELECT * FROM agent_scan_jobs
        WHERE job_kind = 'PUBLIC_POOL' AND status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :batch
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<AgentScanJob> claimPublicPoolJobsWithLock(@Param("batch") int batch);

    /**
     * Looks up a job by id while asserting it is currently owned by a specific agent.
     * Used by submitResult on the AGENT_PINNED path (existing behaviour).
     */
    // findByIdAndAgentId already covers this — kept above.

    /** Latest job for a target — used by scan-status endpoint. */
    @Query("SELECT j FROM AgentScanJob j WHERE j.target.id = :targetId ORDER BY j.createdAt DESC")
    List<AgentScanJob> findByTargetIdOrderByCreatedAtDesc(UUID targetId);

    /** Stale CLAIMED jobs — reset to PENDING after timeout (stale-claim recovery). */
    @Query("SELECT j FROM AgentScanJob j WHERE j.status = 'CLAIMED' AND j.claimedAt < :before")
    List<AgentScanJob> findStaleClaimedJobs(Instant before);

    /** Clean up old completed/failed jobs older than the retention period. */
    @Modifying
    @Query("DELETE FROM AgentScanJob j WHERE j.status IN ('COMPLETED','FAILED') AND j.createdAt < :before")
    int deleteOldCompletedJobs(Instant before);

    /**
     * Counts PENDING + CLAIMED jobs for an org — used by RFC 0010 migration to record
     * in-flight scan work at transfer time (Decision 2: count only, do not block).
     */
    @Query("SELECT COUNT(j) FROM AgentScanJob j WHERE j.orgId = :orgId AND j.status IN ('PENDING','CLAIMED')")
    int countInFlightByOrgId(@Param("orgId") UUID orgId);

    /**
     * Pool starvation check: counts PUBLIC_POOL jobs still PENDING beyond a given age.
     * Used by the pool-starvation alert scheduler (RFC 0013 §5).
     */
    @Query(value = """
        SELECT COUNT(*) FROM agent_scan_jobs
        WHERE job_kind = 'PUBLIC_POOL' AND status = 'PENDING' AND created_at < :before
        """, nativeQuery = true)
    long countStalePoolPendingJobs(@Param("before") Instant before);

    /**
     * Pool starvation check: finds the oldest PUBLIC_POOL PENDING job created before
     * the given threshold (returns empty if none). RFC 0013 §5.
     */
    @Query(value = """
        SELECT * FROM agent_scan_jobs
        WHERE job_kind = 'PUBLIC_POOL' AND status = 'PENDING' AND created_at < :before
        ORDER BY created_at ASC
        LIMIT 1
        """, nativeQuery = true)
    Optional<AgentScanJob> findOldestStalePoolPendingJob(@Param("before") Instant before);

    /**
     * Finds all PUBLIC_POOL PENDING jobs older than the given threshold.
     * Used by PublicScanFallbackScheduler in HYBRID mode to run in-process fallback
     * when no scanner has claimed the job within the stale window (RFC 0013 §7).
     */
    @Query(value = """
        SELECT * FROM agent_scan_jobs
        WHERE job_kind = 'PUBLIC_POOL' AND status = 'PENDING' AND created_at < :before
        ORDER BY created_at ASC
        """, nativeQuery = true)
    List<AgentScanJob> findStalePoolPendingJobs(@Param("before") Instant before);

    /**
     * Finds the two most recent FAILED jobs for a target in descending order.
     * Used to determine the two-consecutive-FAILED threshold for UNREACHABLE hysteresis
     * (RFC 0013 §5).
     */
    @Query(value = """
        SELECT * FROM agent_scan_jobs
        WHERE target_id = :targetId AND status = 'FAILED'
        ORDER BY completed_at DESC NULLS LAST
        LIMIT 2
        """, nativeQuery = true)
    List<AgentScanJob> findLastTwoFailedJobsForTarget(@Param("targetId") UUID targetId);

    /**
     * Deduplication check: true if a PUBLIC_POOL job already exists in PENDING or CLAIMED
     * state for the given target. Used by PublicScanEnqueueScheduler and the triggerScan
     * public path to skip re-enqueue (RFC 0013 §2).
     */
    @Query(value = """
        SELECT COUNT(*) > 0 FROM agent_scan_jobs
        WHERE target_id = :targetId
          AND job_kind = 'PUBLIC_POOL'
          AND status IN ('PENDING', 'CLAIMED')
        """, nativeQuery = true)
    boolean existsActivePoolJobForTarget(@Param("targetId") UUID targetId);
}
