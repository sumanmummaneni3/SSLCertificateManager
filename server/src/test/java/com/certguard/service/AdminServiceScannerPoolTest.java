package com.certguard.service;

import com.certguard.dto.response.ScannerPoolResponse;
import com.certguard.entity.Agent;
import com.certguard.entity.AgentScanJob;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.enums.AgentStatus;
import com.certguard.enums.AgentType;
import com.certguard.enums.OrgType;
import com.certguard.enums.ScanJobStatus;
import com.certguard.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminService#getScannerPool()} — payload shape
 * (RFC 0013 §9 ratified contract). The 403-for-non-platform-admin guard is covered
 * separately by the MockMvc-based RBAC test (requires Testcontainers/Docker).
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceScannerPoolTest {

    @Mock OrganizationRepository orgRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock OrgMemberRepository orgMemberRepository;
    @Mock TargetRepository targetRepository;
    @Mock AgentRepository agentRepository;
    @Mock AgentScanJobRepository scanJobRepository;
    @Mock PlatformAdminAuditRepository auditRepository;
    @Mock OrgService orgService;
    @Mock UserRepository userRepository;

    AdminService adminService;

    Organization org;

    @BeforeEach
    void setUp() {
        adminService = new AdminService(orgRepository, subscriptionRepository, orgMemberRepository,
                targetRepository, agentRepository, scanJobRepository, auditRepository, orgService,
                userRepository);

        org = Organization.builder().name("CertGuard Platform").slug("certguard-platform")
                .orgType(OrgType.SINGLE).build();
    }

    @Test
    void returnsScannersWithClaimedAndCompletedCounts() {
        Agent scanner1 = buildAgent("scanner-1", AgentStatus.ACTIVE);
        Agent scanner2 = buildAgent("scanner-2", AgentStatus.PENDING);

        when(agentRepository.findAllByAgentType(AgentType.PLATFORM_SCANNER))
                .thenReturn(List.of(scanner1, scanner2));

        when(scanJobRepository.countByAgentIdAndClaimedAtAfter(eq(scanner1.getId()), any(Instant.class)))
                .thenReturn(12L);
        when(scanJobRepository.countByAgentIdAndStatus(eq(scanner1.getId()), eq(ScanJobStatus.COMPLETED)))
                .thenReturn(340L);
        when(scanJobRepository.countByAgentIdAndClaimedAtAfter(eq(scanner2.getId()), any(Instant.class)))
                .thenReturn(0L);
        when(scanJobRepository.countByAgentIdAndStatus(eq(scanner2.getId()), eq(ScanJobStatus.COMPLETED)))
                .thenReturn(88L);

        when(scanJobRepository.countStalePoolPendingJobs(any(Instant.class))).thenReturn(0L);
        when(scanJobRepository.findOldestStalePoolPendingJob(any(Instant.class))).thenReturn(Optional.empty());
        when(scanJobRepository.countClaimedPoolJobs()).thenReturn(0L);
        when(scanJobRepository.countFailedPoolJobsSince(any(Instant.class))).thenReturn(0L);

        ScannerPoolResponse response = adminService.getScannerPool();

        assertThat(response.getScanners()).hasSize(2);

        ScannerPoolResponse.ScannerInfo info1 = response.getScanners().stream()
                .filter(s -> s.getId().equals(scanner1.getId())).findFirst().orElseThrow();
        assertThat(info1.getName()).isEqualTo("scanner-1");
        assertThat(info1.getStatus()).isEqualTo("ACTIVE");
        assertThat(info1.getJobsClaimedLastHour()).isEqualTo(12L);
        assertThat(info1.getTotalJobsCompleted()).isEqualTo(340L);

        ScannerPoolResponse.ScannerInfo info2 = response.getScanners().stream()
                .filter(s -> s.getId().equals(scanner2.getId())).findFirst().orElseThrow();
        assertThat(info2.getStatus()).isEqualTo("PENDING");
        assertThat(info2.getJobsClaimedLastHour()).isEqualTo(0L);
        assertThat(info2.getTotalJobsCompleted()).isEqualTo(88L);
    }

    @Test
    void returnsEmptyScannersList_whenNoPlatformScannersRegistered() {
        when(agentRepository.findAllByAgentType(AgentType.PLATFORM_SCANNER)).thenReturn(List.of());
        when(scanJobRepository.countStalePoolPendingJobs(any(Instant.class))).thenReturn(0L);
        when(scanJobRepository.findOldestStalePoolPendingJob(any(Instant.class))).thenReturn(Optional.empty());
        when(scanJobRepository.countClaimedPoolJobs()).thenReturn(0L);
        when(scanJobRepository.countFailedPoolJobsSince(any(Instant.class))).thenReturn(0L);

        ScannerPoolResponse response = adminService.getScannerPool();

        assertThat(response.getScanners()).isEmpty();
        assertThat(response.getBacklog().getPendingCount()).isZero();
    }

    @Test
    void backlog_computesPendingCountAndOldestAgeInMinutes() {
        when(agentRepository.findAllByAgentType(AgentType.PLATFORM_SCANNER)).thenReturn(List.of());

        when(scanJobRepository.countStalePoolPendingJobs(any(Instant.class))).thenReturn(7L);

        Target target = Target.builder().organization(org).host("example.com").port(443)
                .isPrivate(false).build();
        Instant createdAt = Instant.now().minus(23, ChronoUnit.MINUTES);
        AgentScanJob oldestJob = AgentScanJob.builder()
                .agent(null).target(target).orgId(UUID.randomUUID())
                .status(ScanJobStatus.PENDING)
                .jobKind(AgentScanJob.KIND_PUBLIC_POOL)
                .build();
        ReflectionTestUtils.setField(oldestJob, "createdAt", createdAt);

        when(scanJobRepository.findOldestStalePoolPendingJob(any(Instant.class)))
                .thenReturn(Optional.of(oldestJob));
        when(scanJobRepository.countClaimedPoolJobs()).thenReturn(3L);
        when(scanJobRepository.countFailedPoolJobsSince(any(Instant.class))).thenReturn(2L);

        ScannerPoolResponse response = adminService.getScannerPool();

        assertThat(response.getBacklog().getPendingCount()).isEqualTo(7L);
        // ~23 minutes — allow the natural test-execution slack.
        assertThat(response.getBacklog().getOldestPendingAgeMinutes()).isBetween(22L, 24L);
        assertThat(response.getBacklog().getClaimedCount()).isEqualTo(3L);
        assertThat(response.getBacklog().getFailedLast24h()).isEqualTo(2L);
    }

    @Test
    void backlog_zeroWhenNoPoolJobsPending() {
        when(agentRepository.findAllByAgentType(AgentType.PLATFORM_SCANNER)).thenReturn(List.of());
        when(scanJobRepository.countStalePoolPendingJobs(any(Instant.class))).thenReturn(0L);
        when(scanJobRepository.findOldestStalePoolPendingJob(any(Instant.class))).thenReturn(Optional.empty());
        when(scanJobRepository.countClaimedPoolJobs()).thenReturn(0L);
        when(scanJobRepository.countFailedPoolJobsSince(any(Instant.class))).thenReturn(0L);

        ScannerPoolResponse response = adminService.getScannerPool();

        assertThat(response.getBacklog().getPendingCount()).isZero();
        assertThat(response.getBacklog().getOldestPendingAgeMinutes()).isZero();
    }

    private Agent buildAgent(String name, AgentStatus status) {
        Agent agent = Agent.builder()
                .organization(org).name(name).agentKeyHash("hash")
                .agentType(AgentType.PLATFORM_SCANNER)
                .status(status)
                .lastSeenAt(Instant.now())
                .build();
        ReflectionTestUtils.setField(agent, "id", UUID.randomUUID());
        return agent;
    }
}
