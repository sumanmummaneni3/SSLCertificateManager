package com.certguard.service;

import com.certguard.entity.Agent;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.exception.SubscriptionSuspendedException;
import com.certguard.repository.TargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PrivateScanScheduler (RFC 0008 §6).
 *
 * Covers:
 * - Only enabled-private-with-agent targets are passed to queueScanJob
 * - Chunking: repo is called with Pageable; each page triggers queueScanJob per target
 * - Multi-page: all pages are drained
 * - Exception in queueScanJob (SubscriptionSuspendedException, IllegalStateException)
 *   causes log+skip, not sweep abort
 * - Trigger-source is always TRIGGER_SCHEDULED
 * - Empty candidate set: no queueScanJob calls
 */
@ExtendWith(MockitoExtension.class)
class PrivateScanSchedulerTest {

    @Mock TargetRepository targetRepository;
    @Mock AgentService agentService;

    PrivateScanScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PrivateScanScheduler(targetRepository, agentService);
        ReflectionTestUtils.setField(scheduler, "enqueueBatchSize", 500);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Organization buildOrg() {
        Organization org = Organization.builder().name("TestOrg").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        return org;
    }

    private Agent buildAgent() {
        Agent agent = new Agent();
        ReflectionTestUtils.setField(agent, "id", UUID.randomUUID());
        return agent;
    }

    /** Private, enabled, agent assigned — the "happy path" candidate. */
    private Target eligibleTarget(Organization org) {
        Target t = Target.builder()
                .organization(org).host("internal.example.com").port(443)
                .isPrivate(true).enabled(true).agent(buildAgent()).build();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        return t;
    }

    /** Single-page response wrapping the given targets. */
    private Page<Target> singlePage(List<Target> targets) {
        return new PageImpl<>(targets, PageRequest.of(0, 500), targets.size());
    }

    /**
     * Stubs two pages of one target each.
     * Uses pageSize=1 so that the first page reports hasNext()=true (total=2 > 1*1=1).
     * The second page has pageNumber=1 and total=2, so hasNext()=false (2*1=2 >= 2).
     */
    private void stubTwoPages(Target t1, Target t2) {
        Page<Target> page0 = new PageImpl<>(List.of(t1), PageRequest.of(0, 1), 2);
        Page<Target> page1 = new PageImpl<>(List.of(t2), PageRequest.of(1, 1), 2);
        when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                .thenReturn(page0)
                .thenReturn(page1);
    }

    // ── Happy-path / eligibility ───────────────────────────────────────────────

    @Nested
    class Eligibility {

        @Test
        void noCandidates_noQueueCalls() {
            when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                    .thenReturn(singlePage(Collections.emptyList()));

            scheduler.scheduledPrivateScan();

            verifyNoInteractions(agentService);
        }

        @Test
        void singleEligibleTarget_queuesWithScheduledTrigger() {
            Organization org = buildOrg();
            Target t = eligibleTarget(org);

            when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                    .thenReturn(singlePage(List.of(t)));

            scheduler.scheduledPrivateScan();

            verify(agentService).queueScanJob(t, AgentService.TRIGGER_SCHEDULED);
        }

        @Test
        void multipleEligibleTargets_allQueued() {
            Organization org = buildOrg();
            Target t1 = eligibleTarget(org);
            Target t2 = eligibleTarget(org);
            Target t3 = eligibleTarget(org);

            when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                    .thenReturn(singlePage(List.of(t1, t2, t3)));

            scheduler.scheduledPrivateScan();

            // All three must be queued with TRIGGER_SCHEDULED.
            ArgumentCaptor<Target> captor = ArgumentCaptor.forClass(Target.class);
            ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
            verify(agentService, times(3)).queueScanJob(captor.capture(), sourceCaptor.capture());

            assertThat(captor.getAllValues()).containsExactlyInAnyOrder(t1, t2, t3);
            assertThat(sourceCaptor.getAllValues()).containsOnly(AgentService.TRIGGER_SCHEDULED);
        }

        @Test
        void triggerSource_isAlwaysScheduled_notUser() {
            Organization org = buildOrg();
            Target t = eligibleTarget(org);
            when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                    .thenReturn(singlePage(List.of(t)));

            scheduler.scheduledPrivateScan();

            verify(agentService).queueScanJob(any(Target.class), eq(AgentService.TRIGGER_SCHEDULED));
            verify(agentService, never()).queueScanJob(any(Target.class), eq(AgentService.TRIGGER_USER));
        }
    }

    // ── Chunking / pagination ─────────────────────────────────────────────────

    @Nested
    class Chunking {

        @Test
        void repoCalledWithPageable_notFetchAll() {
            when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                    .thenReturn(singlePage(Collections.emptyList()));

            scheduler.scheduledPrivateScan();

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(targetRepository).findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(
                    pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(500);
        }

        @Test
        void customBatchSize_passedToRepo() {
            ReflectionTestUtils.setField(scheduler, "enqueueBatchSize", 10);
            when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0));

            scheduler.scheduledPrivateScan();

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(targetRepository).findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(10);
        }

        @Test
        void multiPageSweep_allPagesQueried_allTargetsQueued() {
            Organization org = buildOrg();
            Target t1 = eligibleTarget(org);
            Target t2 = eligibleTarget(org);

            stubTwoPages(t1, t2);

            scheduler.scheduledPrivateScan();

            // Repo called for page 0 and page 1.
            verify(targetRepository, times(2))
                    .findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class));

            // Both targets were queued.
            verify(agentService).queueScanJob(t1, AgentService.TRIGGER_SCHEDULED);
            verify(agentService).queueScanJob(t2, AgentService.TRIGGER_SCHEDULED);
        }

        @Test
        void multiPageSweep_pageNumberIncrements() {
            Organization org = buildOrg();
            Target t1 = eligibleTarget(org);
            Target t2 = eligibleTarget(org);

            stubTwoPages(t1, t2);

            scheduler.scheduledPrivateScan();

            ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(targetRepository, times(2))
                    .findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(pageCaptor.capture());

            List<Pageable> pages = pageCaptor.getAllValues();
            assertThat(pages.get(0).getPageNumber()).isZero();
            assertThat(pages.get(1).getPageNumber()).isEqualTo(1);
        }
    }

    // ── Skip / error handling ──────────────────────────────────────────────────

    @Nested
    class SkipHandling {

        @Test
        void subscriptionSuspended_logAndContinue_doesNotAbortSweep() {
            Organization org = buildOrg();
            Target suspended = eligibleTarget(org);
            Target healthy   = eligibleTarget(org);

            when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                    .thenReturn(singlePage(List.of(suspended, healthy)));

            // First target throws SubscriptionSuspendedException; second should still be queued.
            doThrow(new SubscriptionSuspendedException("TestOrg"))
                    .when(agentService).queueScanJob(eq(suspended), anyString());

            scheduler.scheduledPrivateScan();

            // healthy target still queued despite the earlier failure.
            verify(agentService).queueScanJob(healthy, AgentService.TRIGGER_SCHEDULED);
        }

        @Test
        void illegalStateException_noAgent_logAndContinue() {
            Organization org = buildOrg();
            Target noAgent = eligibleTarget(org);
            Target good    = eligibleTarget(org);

            when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                    .thenReturn(singlePage(List.of(noAgent, good)));

            doThrow(new IllegalStateException("Target has no assigned agent"))
                    .when(agentService).queueScanJob(eq(noAgent), anyString());

            scheduler.scheduledPrivateScan();

            verify(agentService).queueScanJob(good, AgentService.TRIGGER_SCHEDULED);
        }

        @Test
        void unexpectedException_logAndContinue_restOfChunkQueued() {
            Organization org = buildOrg();
            Target bad  = eligibleTarget(org);
            Target good = eligibleTarget(org);

            when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                    .thenReturn(singlePage(List.of(bad, good)));

            doThrow(new RuntimeException("unexpected"))
                    .when(agentService).queueScanJob(eq(bad), anyString());

            scheduler.scheduledPrivateScan();

            verify(agentService).queueScanJob(good, AgentService.TRIGGER_SCHEDULED);
        }

        @Test
        void allTargetsSkipped_sweepCompletesNormally() {
            Organization org = buildOrg();
            Target t1 = eligibleTarget(org);
            Target t2 = eligibleTarget(org);

            when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                    .thenReturn(singlePage(List.of(t1, t2)));

            doThrow(new SubscriptionSuspendedException("Org"))
                    .when(agentService).queueScanJob(any(Target.class), anyString());

            // Must not throw.
            scheduler.scheduledPrivateScan();
        }

        @Test
        void mixedChunk_countReflectsQueuedVsSkipped() {
            // Verify the chunk result array: [queued, skipped].
            Organization org = buildOrg();
            Target good    = eligibleTarget(org);
            Target skipped = eligibleTarget(org);

            // Use a doAnswer to throw only for the specific target identity, avoiding
            // Mockito strict-stubbing false positives when eq() matcher is evaluated
            // before the call is dispatched.
            doAnswer(invocation -> {
                Target t = invocation.getArgument(0);
                if (t == skipped) {
                    throw new SubscriptionSuspendedException("Org");
                }
                return null;
            }).when(agentService).queueScanJob(any(Target.class), anyString());

            int[] result = scheduler.enqueueChunk(List.of(good, skipped));

            assertThat(result[0]).isEqualTo(1); // queued
            assertThat(result[1]).isEqualTo(1); // skipped
        }
    }

    // ── Chunking does not call per-target repo queries ────────────────────────

    @Nested
    class NoPlusOneRepo {

        @Test
        void repoQueriedOnlyViaPagedFinder_neverByTargetId() {
            Organization org = buildOrg();
            Target t = eligibleTarget(org);
            when(targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class)))
                    .thenReturn(singlePage(List.of(t)));

            scheduler.scheduledPrivateScan();

            // The only repo interaction must be the paged finder.
            verify(targetRepository, times(1))
                    .findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(any(Pageable.class));
            verify(targetRepository, never()).findById(any());
            verify(targetRepository, never()).findByIdAndOrganizationId(any(), any());
        }
    }
}
