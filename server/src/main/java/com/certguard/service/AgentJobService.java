package com.certguard.service;

import com.certguard.client.RenewalServiceClient;
import com.certguard.dto.request.AgentJobReportRequest;
import com.certguard.dto.response.DeliveryJobResponse;
import com.certguard.entity.Agent;
import com.certguard.entity.AgentJob;
import com.certguard.enums.AgentJobStatus;
import com.certguard.enums.AgentJobType;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.AgentJobRepository;
import com.certguard.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AgentJobService {

    private final AgentJobRepository agentJobRepository;
    private final AgentRepository agentRepository;
    private final RenewalServiceClient renewalServiceClient;

    @Value("${app.agent.job.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.agent.job.stale-claim-minutes:10}")
    private int staleClaimMinutes;

    /**
     * Generic job enqueue used by InternalAgentJobController (called from renewal service).
     * Idempotent — returns existing active job if dedupKey matches.
     */
    @Transactional
    public AgentJob enqueueJob(UUID renewalId, UUID agentId, UUID orgId, UUID targetId,
                                AgentJobType jobType, Map<String, Object> payload, String dedupKey) {
        List<AgentJobStatus> activeStatuses = List.of(AgentJobStatus.PENDING, AgentJobStatus.CLAIMED);
        Optional<AgentJob> existing = agentJobRepository.findFirstByDedupKeyAndStatusIn(dedupKey, activeStatuses);
        if (existing.isPresent()) {
            log.debug("Dedup: returning existing active agent job {} ({})", existing.get().getId(), dedupKey);
            return existing.get();
        }

        Agent agentRef = agentRepository.getReferenceById(agentId);

        AgentJob job = AgentJob.builder()
                .agent(agentRef)
                .orgId(orgId)
                .targetId(targetId)
                .renewalId(renewalId)
                .jobType(jobType)
                .status(AgentJobStatus.PENDING)
                .payload(payload)
                .dedupKey(dedupKey)
                .build();

        return agentJobRepository.save(job);
    }

    /**
     * Cancels a pending or claimed job. No-op if already in a terminal state.
     */
    @Transactional
    public void cancelJob(UUID jobId) {
        agentJobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == AgentJobStatus.PENDING
                    || job.getStatus() == AgentJobStatus.CLAIMED) {
                job.setStatus(AgentJobStatus.FAILED);
                job.setErrorCode("CANCELLED");
                job.setErrorDetail("Cancelled by renewal service");
                agentJobRepository.save(job);
                log.info("Agent job {} cancelled", jobId);
            }
        });
    }

    /**
     * Claims pending CERT_DELIVERY jobs for the agent using FOR UPDATE SKIP LOCKED,
     * increments attempt_count, and returns them as DeliveryJobResponse.
     */
    @Transactional
    public List<DeliveryJobResponse> claimDeliveryJobs(Agent agent) {
        List<AgentJob> pending = agentJobRepository.claimPendingDeliveryJobsWithLock(
                agent.getId(), agent.getMaxTargets());

        Instant now = Instant.now();
        pending.forEach(job -> {
            job.setStatus(AgentJobStatus.CLAIMED);
            job.setClaimedAt(now);
            job.setAttemptCount(job.getAttemptCount() + 1);
        });
        agentJobRepository.saveAll(pending);

        return pending.stream().map(this::toDeliveryJobResponse).collect(Collectors.toList());
    }

    /**
     * Marks a job COMPLETED. For CERT_DELIVERY jobs, notifies the renewal service.
     */
    @Transactional
    public void completeJob(UUID jobId, Agent agent, AgentJobReportRequest rep) {
        AgentJob job = loadJobForAgent(jobId, agent);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        agentJobRepository.save(job);

        if (AgentJobType.CERT_DELIVERY.equals(job.getJobType()) && job.getRenewalId() != null) {
            log.info("Delivery complete — renewalId: {}, notifying renewal service", job.getRenewalId());
            renewalServiceClient.notifyDeliveryCompleted(job.getRenewalId());
        }
    }

    /**
     * Marks a job FAILED and notifies the renewal service.
     */
    @Transactional
    public void failJob(UUID jobId, Agent agent, AgentJobReportRequest rep) {
        AgentJob job = loadJobForAgent(jobId, agent);
        job.setStatus(AgentJobStatus.FAILED);
        job.setErrorCode(rep.errorCode());
        job.setErrorDetail(rep.errorDetail());
        agentJobRepository.save(job);

        if (job.getRenewalId() != null) {
            log.warn("Job failed — renewalId: {}, errorCode: {}, notifying renewal service",
                    job.getRenewalId(), rep.errorCode());
            renewalServiceClient.notifyDeliveryFailed(job.getRenewalId(), rep.errorDetail());
        }
    }

    /**
     * Resets CLAIMED jobs older than {@code stale-claim-minutes} back to PENDING.
     * If attempt_count >= max-attempts, marks terminal FAILED and notifies renewal service.
     */
    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "AgentJobService_resetStaleClaimedJobs",
                   lockAtMostFor = "PT5M", lockAtLeastFor = "PT4M")
    @Transactional
    public void resetStaleClaimedJobs() {
        Instant threshold = Instant.now().minus(staleClaimMinutes, ChronoUnit.MINUTES);
        List<AgentJob> stale = agentJobRepository.findStaleClaimedJobs(threshold);
        if (stale.isEmpty()) return;

        for (AgentJob job : stale) {
            if (job.getAttemptCount() >= maxAttempts) {
                job.setStatus(AgentJobStatus.FAILED);
                job.setErrorCode("MAX_ATTEMPTS_EXCEEDED");
                String reason = "Exceeded maximum retry attempts (" + maxAttempts + ")";
                job.setErrorDetail(reason);
                log.warn("Agent job {} exceeded max attempts — marking FAILED (renewalId: {})",
                        job.getId(), job.getRenewalId());
                if (job.getRenewalId() != null) {
                    renewalServiceClient.notifyDeliveryFailed(job.getRenewalId(), reason);
                }
            } else {
                job.setStatus(AgentJobStatus.PENDING);
                job.setClaimedAt(null);
                log.warn("Reset stale CLAIMED agent job {} (renewalId: {})",
                        job.getId(), job.getRenewalId());
            }
        }
        agentJobRepository.saveAll(stale);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private AgentJob loadJobForAgent(UUID jobId, Agent agent) {
        return agentJobRepository.findByIdAndAgentId(jobId, agent.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Job not found for this agent: " + jobId));
    }

    @SuppressWarnings("unchecked")
    private DeliveryJobResponse toDeliveryJobResponse(AgentJob job) {
        Map<String, Object> p = job.getPayload();
        return new DeliveryJobResponse(
                job.getId(),
                job.getTargetId(),
                job.getJobType(),
                p != null ? (String) p.get("packageId") : null,
                p != null ? (String) p.get("targetLocation") : null,
                p != null ? (String) p.get("checksumSha256") : null,
                p != null ? (String) p.get("fileName") : null,
                p != null ? (String) p.get("commonName") : null,
                p != null ? (List<String>) p.get("sans") : null
        );
    }
}
