package com.certguard.controller;

import com.certguard.dto.internal.InternalEnqueueJobRequest;
import com.certguard.dto.internal.InternalEnqueueJobResponse;
import com.certguard.entity.AgentJob;
import com.certguard.service.AgentJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Internal REST API consumed exclusively by certguard-renewal-service.
 * Secured via InternalServiceAuthFilter (Bearer token).
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1/agent-jobs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL_SERVICE')")
public class InternalAgentJobController {

    private final AgentJobService agentJobService;

    /**
     * Enqueues a CERT_RENEW_CSR or CERT_DELIVERY agent job.
     * Idempotent via dedupKey — returns existing active job if found.
     */
    @PostMapping
    public ResponseEntity<InternalEnqueueJobResponse> enqueueJob(
            @RequestBody InternalEnqueueJobRequest req) {

        AgentJob job = agentJobService.enqueueJob(
                req.renewalId(), req.agentId(), req.orgId(), req.targetId(),
                req.jobType(), req.payload(), req.dedupKey());

        log.info("Internal: enqueued agent job {} ({}) for renewalId: {}",
                job.getId(), req.jobType(), req.renewalId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new InternalEnqueueJobResponse(job.getId()));
    }

    /**
     * Cancels a pending or claimed agent job. No-op if already terminal.
     */
    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<Void> cancelJob(@PathVariable UUID jobId) {
        agentJobService.cancelJob(jobId);
        log.info("Internal: cancelled agent job {}", jobId);
        return ResponseEntity.noContent().build();
    }
}
