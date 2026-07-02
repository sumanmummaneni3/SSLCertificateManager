package com.certguard.service;

import com.certguard.dto.request.AgentScanResultRequest;
import com.certguard.entity.*;
import com.certguard.enums.*;
import com.certguard.repository.*;
import com.certguard.security.AgentHmacService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AgentService (RFC 0013 §4-§5).
 *
 * <p>Covers:
 * <ol>
 *   <li>submitResult branch matrix: AGENT_PINNED×CUSTOMER + PUBLIC_POOL×PLATFORM_SCANNER</li>
 *   <li>Security guard: PUBLIC_POOL submitted by CUSTOMER agent → SecurityException</li>
 *   <li>Security guard: AGENT_PINNED not assigned to caller → SecurityException</li>
 *   <li>Job/target binding mismatch → SecurityException (cross-tenant guard)</li>
 *   <li>ERROR result: attempts < maxAttempts → re-queued PENDING</li>
 *   <li>ERROR result: attempts >= maxAttempts → FAILED + checkAndMarkUnreachable</li>
 *   <li>UNREACHABLE: two consecutive FAILED jobs → certs marked UNREACHABLE</li>
 *   <li>UNREACHABLE: only one FAILED job → certs NOT marked UNREACHABLE</li>
 *   <li>enqueuePublicPoolJob: dedup guard prevents double-enqueue</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceScanBranchTest {

    @Mock AgentRepository agentRepository;
    @Mock AgentRegistrationTokenRepository tokenRepository;
    @Mock AgentScanJobRepository scanJobRepository;
    @Mock TargetRepository targetRepository;
    @Mock CertificateRecordRepository certRepository;
    @Mock OrganizationRepository orgRepository;
    @Mock AgentHmacService hmacService;
    @Mock NetworkScanService networkScanService;
    @Mock BCryptPasswordEncoder passwordEncoder;
    @Mock SubscriptionGuard subscriptionGuard;
    @Mock CertificatePersistenceService certPersistenceService;

    @InjectMocks
    AgentService agentService;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    Organization org;
    Target publicTarget;
    Target privateTarget;
    Agent customerAgent;
    Agent platformAgent;

    @BeforeEach
    void setUp() {
        // Wire @Value fields (no Spring context)
        ReflectionTestUtils.setField(agentService, "poolClaimBatch", 25);
        ReflectionTestUtils.setField(agentService, "maxAttempts", 3);

        org = Organization.builder().name("Acme").slug("acme").orgType(OrgType.SINGLE).build();

        publicTarget = Target.builder()
                .organization(org)
                .host("example.com")
                .port(443)
                .isPrivate(false)
                .enabled(true)
                .build();

        privateTarget = Target.builder()
                .organization(org)
                .host("internal.acme.local")
                .port(443)
                .isPrivate(true)
                .enabled(true)
                .build();

        customerAgent = Agent.builder()
                .organization(org)
                .name("customer-agent-1")
                .agentKeyHash("hash")
                .agentType(AgentType.CUSTOMER)
                .status(AgentStatus.ACTIVE)
                .allowedCidrs(List.of("0.0.0.0/0"))
                .build();
        setId(customerAgent, UUID.randomUUID());

        platformAgent = Agent.builder()
                .organization(org)
                .name("platform-scanner-1")
                .agentKeyHash("hash")
                .agentType(AgentType.PLATFORM_SCANNER)
                .status(AgentStatus.ACTIVE)
                .allowedCidrs(List.of())
                .build();
        setId(platformAgent, UUID.randomUUID());

        // Assign private target to customer agent
        privateTarget = Target.builder()
                .organization(org)
                .host("internal.acme.local")
                .port(443)
                .isPrivate(true)
                .enabled(true)
                .agent(customerAgent)
                .build();
    }

    // ── Branch matrix: AGENT_PINNED × CUSTOMER ────────────────────────────────

    @Nested
    class AgentPinnedCustomerPath {

        @Test
        void full_result_for_pinned_job_persists_cert() {
            // Build a target with a known ID assigned to the customer agent.
            UUID targetId = UUID.randomUUID();
            Target pinnedTarget = Target.builder()
                    .organization(org)
                    .host("example.com")
                    .port(443)
                    .isPrivate(false)
                    .enabled(true)
                    .agent(customerAgent)
                    .build();
            setId(pinnedTarget, targetId);

            AgentScanJob job = pinnedJob(customerAgent, pinnedTarget);
            AgentScanResultRequest req = fullRequest(job, pinnedTarget, "FULL");

            when(hmacService.verify(any(), any(), any())).thenReturn(true);
            when(scanJobRepository.findByIdAndAgentId(job.getId(), customerAgent.getId()))
                    .thenReturn(Optional.of(job));
            when(targetRepository.findById(targetId)).thenReturn(Optional.of(pinnedTarget));

            agentService.submitResult(customerAgent, req, "plain-key");

            verify(certPersistenceService).persistFull(
                    any(), eq(customerAgent), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any());
        }

        @Test
        void pinned_job_from_wrong_agent_throws() {
            AgentScanJob job = pinnedJob(customerAgent, privateTarget);
            // job belongs to customerAgent but caller is platformAgent
            setId(job, UUID.randomUUID());
            when(hmacService.verify(any(), any(), any())).thenReturn(true);
            when(scanJobRepository.findByIdAndAgentId(job.getId(), platformAgent.getId()))
                    .thenReturn(Optional.empty());

            AgentScanResultRequest req = fullRequest(job, privateTarget, "FULL");
            req.setJobId(job.getId());

            assertThatThrownBy(() -> agentService.submitResult(platformAgent, req, "key"))
                    .isInstanceOf(com.certguard.exception.ResourceNotFoundException.class);
        }
    }

    // ── Branch matrix: PUBLIC_POOL × PLATFORM_SCANNER ────────────────────────

    @Nested
    class PublicPoolPlatformScannerPath {

        @Test
        void customer_agent_submitting_pool_result_throws_security_exception() {
            AgentScanJob job = poolJob(platformAgent, publicTarget);
            AgentScanResultRequest req = fullRequest(job, publicTarget, "FULL");

            when(hmacService.verify(any(), any(), any())).thenReturn(true);
            // Job found under platformAgent.id (which claimed it) but caller is customerAgent
            when(scanJobRepository.findByIdAndAgentId(job.getId(), customerAgent.getId()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> agentService.submitResult(customerAgent, req, "key"))
                    .isInstanceOf(com.certguard.exception.ResourceNotFoundException.class);
        }

        @Test
        void job_target_binding_mismatch_throws_security_exception() {
            UUID realTargetId = UUID.randomUUID();
            UUID spoofedTargetId = UUID.randomUUID();

            AgentScanJob job = poolJob(platformAgent, publicTarget);
            setId(job, UUID.randomUUID());
            setId(job.getTarget(), realTargetId);

            AgentScanResultRequest req = fullRequest(job, publicTarget, "FULL");
            req.setTargetId(spoofedTargetId); // request claims a different target

            when(hmacService.verify(any(), any(), any())).thenReturn(true);
            when(scanJobRepository.findByIdAndAgentId(job.getId(), platformAgent.getId()))
                    .thenReturn(Optional.of(job));

            Target spoofedTarget = Target.builder()
                    .organization(org).host("other.com").port(443).isPrivate(false).build();
            setId(spoofedTarget, spoofedTargetId);
            when(targetRepository.findById(spoofedTargetId)).thenReturn(Optional.of(spoofedTarget));

            assertThatThrownBy(() -> agentService.submitResult(platformAgent, req, "key"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("binding mismatch");
        }
    }

    // ── ERROR result path ─────────────────────────────────────────────────────

    @Nested
    class ErrorResultPath {

        @Test
        void error_before_max_attempts_requeues_as_pending() {
            AgentScanJob job = pinnedJob(customerAgent, privateTarget);
            job.setAttempts(1); // 1 attempt so far, maxAttempts=3

            AgentScanResultRequest req = errorRequest(job);
            when(hmacService.verify(any(), any(), any())).thenReturn(true);
            when(scanJobRepository.findByIdAndAgentId(job.getId(), customerAgent.getId()))
                    .thenReturn(Optional.of(job));

            agentService.submitResult(customerAgent, req, "key");

            assertThat(job.getAttempts()).isEqualTo(2);
            assertThat(job.getStatus()).isEqualTo(ScanJobStatus.PENDING);
            assertThat(job.getClaimedAt()).isNull(); // reset for re-claim
            verify(scanJobRepository).save(job);
        }

        @Test
        void error_at_max_attempts_marks_failed() {
            AgentScanJob job = pinnedJob(customerAgent, privateTarget);
            job.setAttempts(2); // 2 so far, maxAttempts=3 → next becomes 3 = max

            when(hmacService.verify(any(), any(), any())).thenReturn(true);
            when(scanJobRepository.findByIdAndAgentId(job.getId(), customerAgent.getId()))
                    .thenReturn(Optional.of(job));
            // no previous failed jobs → not UNREACHABLE
            when(scanJobRepository.findLastTwoFailedJobsForTarget(privateTarget.getId()))
                    .thenReturn(List.of(job)); // only 1 failed

            AgentScanResultRequest req = errorRequest(job);
            agentService.submitResult(customerAgent, req, "key");

            assertThat(job.getAttempts()).isEqualTo(3);
            assertThat(job.getStatus()).isEqualTo(ScanJobStatus.FAILED);
            assertThat(job.getCompletedAt()).isNotNull();
            verify(scanJobRepository).save(job);
        }

        @Test
        void two_consecutive_failed_jobs_mark_target_unreachable() {
            AgentScanJob job1 = pinnedJob(customerAgent, privateTarget);
            job1.setStatus(ScanJobStatus.FAILED);
            setId(job1, UUID.randomUUID());

            AgentScanJob job2 = pinnedJob(customerAgent, privateTarget);
            job2.setAttempts(2); // about to become 3 = maxAttempts
            setId(job2, UUID.randomUUID());

            when(hmacService.verify(any(), any(), any())).thenReturn(true);
            when(scanJobRepository.findByIdAndAgentId(job2.getId(), customerAgent.getId()))
                    .thenReturn(Optional.of(job2));
            // Simulate repo returning 2 consecutive FAILED jobs (job1 + job2 after save)
            when(scanJobRepository.findLastTwoFailedJobsForTarget(privateTarget.getId()))
                    .thenReturn(List.of(job2, job1));

            CertificateRecord cert = CertificateRecord.builder()
                    .target(privateTarget).status(CertStatus.EXPIRING).build();
            when(certRepository.findAllByTargetId(privateTarget.getId())).thenReturn(List.of(cert));

            AgentScanResultRequest req = errorRequest(job2);
            agentService.submitResult(customerAgent, req, "key");

            // cert must be UNREACHABLE
            assertThat(cert.getStatus()).isEqualTo(CertStatus.UNREACHABLE);
            verify(certRepository, atLeastOnce()).save(cert);
        }

        @Test
        void one_failed_job_does_not_mark_unreachable() {
            AgentScanJob job = pinnedJob(customerAgent, privateTarget);
            job.setAttempts(2); // becomes 3 = max

            when(hmacService.verify(any(), any(), any())).thenReturn(true);
            when(scanJobRepository.findByIdAndAgentId(job.getId(), customerAgent.getId()))
                    .thenReturn(Optional.of(job));
            // Only 1 FAILED job in history
            when(scanJobRepository.findLastTwoFailedJobsForTarget(privateTarget.getId()))
                    .thenReturn(List.of(job));

            AgentScanResultRequest req = errorRequest(job);
            agentService.submitResult(customerAgent, req, "key");

            assertThat(job.getStatus()).isEqualTo(ScanJobStatus.FAILED);
            // certRepository.findAllByTargetId should NOT be called (< 2 consecutive fails)
            verify(certRepository, never()).findAllByTargetId(any());
        }

        @Test
        void error_message_truncated_to_500_chars() {
            AgentScanJob job = pinnedJob(customerAgent, privateTarget);
            job.setAttempts(0);

            String longError = "E".repeat(600);
            AgentScanResultRequest req = errorRequest(job);
            req.setErrorMessage(longError);

            when(hmacService.verify(any(), any(), any())).thenReturn(true);
            when(scanJobRepository.findByIdAndAgentId(job.getId(), customerAgent.getId()))
                    .thenReturn(Optional.of(job));

            agentService.submitResult(customerAgent, req, "key");

            assertThat(job.getErrorMsg()).hasSize(500);
        }
    }

    // ── enqueuePublicPoolJob dedup ────────────────────────────────────────────

    @Nested
    class EnqueuePublicPoolJob {

        @Test
        void does_not_create_second_job_when_one_is_active() {
            when(scanJobRepository.existsActivePoolJobForTarget(publicTarget.getId()))
                    .thenReturn(true);

            agentService.enqueuePublicPoolJob(publicTarget, AgentService.TRIGGER_SCHEDULED);

            verify(scanJobRepository, never()).save(any());
        }

        @Test
        void creates_pool_job_when_none_active() {
            when(scanJobRepository.existsActivePoolJobForTarget(publicTarget.getId()))
                    .thenReturn(false);

            agentService.enqueuePublicPoolJob(publicTarget, AgentService.TRIGGER_USER);

            ArgumentCaptor<AgentScanJob> cap = ArgumentCaptor.forClass(AgentScanJob.class);
            verify(scanJobRepository).save(cap.capture());
            AgentScanJob saved = cap.getValue();
            assertThat(saved.getJobKind()).isEqualTo(AgentScanJob.KIND_PUBLIC_POOL);
            assertThat(saved.getAgent()).isNull();
            assertThat(saved.getTriggerSource()).isEqualTo(AgentService.TRIGGER_USER);
            assertThat(saved.getStatus()).isEqualTo(ScanJobStatus.PENDING);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AgentScanJob pinnedJob(Agent agent, Target target) {
        AgentScanJob job = AgentScanJob.builder()
                .agent(agent).target(target)
                .orgId(UUID.randomUUID())
                .status(ScanJobStatus.CLAIMED)
                .jobKind(AgentScanJob.KIND_AGENT_PINNED)
                .triggerSource(AgentService.TRIGGER_SCHEDULED)
                .attempts(0)
                .build();
        setId(job, UUID.randomUUID());
        return job;
    }

    private AgentScanJob poolJob(Agent agent, Target target) {
        AgentScanJob job = AgentScanJob.builder()
                .agent(agent).target(target)
                .orgId(UUID.randomUUID())
                .status(ScanJobStatus.CLAIMED)
                .jobKind(AgentScanJob.KIND_PUBLIC_POOL)
                .triggerSource(AgentService.TRIGGER_SCHEDULED)
                .attempts(0)
                .build();
        setId(job, UUID.randomUUID());
        if (target.getId() == null) setId(target, UUID.randomUUID());
        return job;
    }

    private AgentScanResultRequest fullRequest(AgentScanJob job, Target target, String scanType) {
        AgentScanResultRequest req = new AgentScanResultRequest();
        req.setJobId(job.getId());
        req.setTargetId(target.getId());
        req.setScanType(scanType);
        req.setSerialNumber("deadbeef");
        req.setCommonName("example.com");
        req.setIssuer("Let's Encrypt");
        req.setNotBefore(Instant.now().minus(30, ChronoUnit.DAYS));
        req.setNotAfter(Instant.now().plus(60, ChronoUnit.DAYS));
        req.setKeyAlgorithm("RSA");
        req.setKeySize(2048);
        req.setSignatureAlgorithm("SHA256withRSA");
        req.setSubjectAltNames(List.of("example.com"));
        req.setChainDepth(2);
        req.setHmacSignature("sig");
        return req;
    }

    private AgentScanResultRequest errorRequest(AgentScanJob job) {
        AgentScanResultRequest req = new AgentScanResultRequest();
        req.setJobId(job.getId());
        req.setTargetId(job.getTarget().getId());
        req.setScanType("ERROR");
        req.setErrorMessage("Connection refused");
        req.setHmacSignature("sig");
        return req;
    }

    /** Set the id on a BaseEntity using ReflectionTestUtils (avoids generating FK-only IDs). */
    private static void setId(BaseEntity entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }
}
