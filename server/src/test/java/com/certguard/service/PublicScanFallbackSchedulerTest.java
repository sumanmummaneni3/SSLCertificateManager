package com.certguard.service;

import com.certguard.entity.AgentScanJob;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.enums.OrgType;
import com.certguard.enums.ScanJobStatus;
import com.certguard.repository.AgentScanJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PublicScanFallbackScheduler} (RFC 0013 §7, architect review m2 —
 * "HYBRID fallback FAILED path never calls checkAndMarkUnreachable — inconsistent with
 * the agent ERROR path").
 */
@ExtendWith(MockitoExtension.class)
class PublicScanFallbackSchedulerTest {

    @Mock AgentScanJobRepository scanJobRepository;
    @Mock SslScannerService sslScannerService;
    @Mock AgentService agentService;

    PublicScanFallbackScheduler scheduler;

    Organization org;
    Target target;
    AgentScanJob job;

    @BeforeEach
    void setUp() {
        scheduler = new PublicScanFallbackScheduler(scanJobRepository, sslScannerService, agentService);
        ReflectionTestUtils.setField(scheduler, "scanningMode", "HYBRID");
        ReflectionTestUtils.setField(scheduler, "fallbackPendingMinutes", 10);

        org = Organization.builder().name("Acme").slug("acme").orgType(OrgType.SINGLE).build();
        target = Target.builder()
                .organization(org).host("example.com").port(443).isPrivate(false).build();
        job = AgentScanJob.builder()
                .agent(null).target(target).orgId(UUID.randomUUID())
                .status(ScanJobStatus.PENDING)
                .jobKind(AgentScanJob.KIND_PUBLIC_POOL)
                .triggerSource(AgentService.TRIGGER_SCHEDULED)
                .build();
        // NOTE: findStalePoolPendingJobs is stubbed per-test (not here) — the
        // ModeGuard.doesNothing_whenNotHybridMode test returns before ever calling it,
        // and Mockito's strict stubbing flags unused @BeforeEach stubs as errors.
    }

    private void stubStaleJobs() {
        when(scanJobRepository.findStalePoolPendingJobs(any(Instant.class)))
                .thenReturn(List.of(job));
    }

    @Nested
    class FailedScanPath {

        @Test
        void wiresHysteresisCheck_whenFallbackScanReturnsFalse() {
            stubStaleJobs();
            when(sslScannerService.executeFallbackScan(eq(target), any()))
                    .thenReturn(false);

            scheduler.runFallback();

            // m2: FAILED path must call the same hysteresis check the agent ERROR path uses.
            verify(agentService).checkAndMarkUnreachable(target);
        }

        @Test
        void doesNotCallHysteresisCheck_whenFallbackScanSucceeds() {
            stubStaleJobs();
            when(sslScannerService.executeFallbackScan(eq(target), any()))
                    .thenReturn(true);

            scheduler.runFallback();

            verify(agentService, never()).checkAndMarkUnreachable(any());
        }
    }

    @Nested
    class ExceptionPath {

        @Test
        void wiresHysteresisCheck_whenFallbackScanThrows() {
            stubStaleJobs();
            when(sslScannerService.executeFallbackScan(eq(target), any()))
                    .thenThrow(new RuntimeException("unexpected"));

            scheduler.runFallback();

            // The outer catch block must also trigger the hysteresis check —
            // consistency across both FAILED-producing paths in this scheduler.
            verify(agentService).checkAndMarkUnreachable(target);
        }
    }

    @Nested
    class ModeGuard {

        @Test
        void doesNothing_whenNotHybridMode() {
            ReflectionTestUtils.setField(scheduler, "scanningMode", "DIRECT");

            scheduler.runFallback();

            verifyNoInteractions(sslScannerService);
            verifyNoInteractions(agentService);
        }
    }
}
