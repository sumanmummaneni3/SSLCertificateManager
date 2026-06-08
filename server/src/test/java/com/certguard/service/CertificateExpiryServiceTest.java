package com.certguard.service;

import com.certguard.entity.CertificateRecord;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.repository.CertificateRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for CertificateExpiryScheduler after the RFC 0008 §2 refactor.
 *
 * The scheduler is now a thin shell: it fetches expiring certs and delegates
 * all per-cert logic to ExpiryEvaluationService. Tests verify that the scheduler
 * correctly queries for in-window certs and passes them to the service in
 * SCHEDULED mode.
 */
@ExtendWith(MockitoExtension.class)
class CertificateExpiryServiceTest {

    @Mock CertificateRecordRepository certRepository;
    @Mock ExpiryEvaluationService expiryEvaluationService;

    CertificateExpiryScheduler scheduler;

    UUID orgId;
    Organization org;

    @BeforeEach
    void setUp() {
        scheduler = new CertificateExpiryScheduler(certRepository, expiryEvaluationService);
        // warningDays is no longer a @Value field on the scheduler (RFC 0008 §3.3 —
        // the scheduler uses resolveMaxWarningDays() instead).  Stub the service so
        // tests that don't override it get the expected 30-day window.
        lenient().when(expiryEvaluationService.resolveMaxWarningDays()).thenReturn(30);

        orgId = UUID.randomUUID();
        org = Organization.builder().name("Org").build();
        ReflectionTestUtils.setField(org, "id", orgId);
    }

    private Target enabledTarget() {
        return Target.builder().organization(org).host("example.com").port(443).enabled(true).build();
    }

    private CertificateRecord certExpiring(Target target, int daysFromNow) {
        CertificateRecord cert = CertificateRecord.builder()
                .target(target)
                .orgId(orgId)
                .commonName("example.com")
                .issuer("Test CA")
                .serialNumber("123")
                .expiryDate(Instant.now().plus(daysFromNow, ChronoUnit.DAYS))
                .notBefore(Instant.now().minus(30, ChronoUnit.DAYS))
                .build();
        ReflectionTestUtils.setField(cert, "id", UUID.randomUUID());
        return cert;
    }

    @Nested
    class SweepDelegation {

        @Test
        void noCertsInWindow_evaluationServiceNotCalled() {
            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of());

            scheduler.checkExpiringCertificates();

            // resolveMaxWarningDays() IS called to build the fetch window — but
            // evaluateAndNotify must NOT be called when the result set is empty.
            verify(expiryEvaluationService, never()).evaluateAndNotify(anyCollection(), any());
        }

        @Test
        void certsInWindow_delegatesToEvaluationServiceWithScheduledMode() {
            Target target = enabledTarget();
            CertificateRecord cert1 = certExpiring(target, 20);
            CertificateRecord cert2 = certExpiring(target, 5);

            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of(cert1, cert2));
            when(expiryEvaluationService.evaluateAndNotify(
                    anyCollection(),
                    eq(ExpiryEvaluationService.EvaluationMode.SCHEDULED)))
                    .thenReturn(2);

            scheduler.checkExpiringCertificates();

            verify(expiryEvaluationService).evaluateAndNotify(
                    argThat((Collection<CertificateRecord> c) -> c.size() == 2),
                    eq(ExpiryEvaluationService.EvaluationMode.SCHEDULED));
        }

        @Test
        void schedulerQueriesWithCorrectWarningCutoff() {
            // resolveMaxWarningDays returns 30 (stubbed in setUp via lenient).
            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of());

            Instant before = Instant.now();
            scheduler.checkExpiringCertificates();
            Instant after = Instant.now();

            ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(certRepository).findExpiringWithTargets(nowCaptor.capture(), cutoffCaptor.capture());

            Instant capturedNow = nowCaptor.getValue();
            Instant capturedCutoff = cutoffCaptor.getValue();
            assertThat(capturedNow).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
            long daysBetween = ChronoUnit.DAYS.between(capturedNow, capturedCutoff);
            assertThat(daysBetween).isEqualTo(30L);
        }

        @Test
        void schedulerUsesResolveMaxWarningDays_widenedWindow_queriesWithCorrectDays() {
            // When some target has warningDays=60, the fetch window must be 60, not 30.
            when(expiryEvaluationService.resolveMaxWarningDays()).thenReturn(60);
            when(certRepository.findExpiringWithTargets(any(), any())).thenReturn(List.of());

            scheduler.checkExpiringCertificates();

            ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(certRepository).findExpiringWithTargets(nowCaptor.capture(), cutoffCaptor.capture());

            long daysBetween = ChronoUnit.DAYS.between(nowCaptor.getValue(), cutoffCaptor.getValue());
            assertThat(daysBetween).isEqualTo(60L);
        }

        @Test
        void certRepository_stampAlertSentAt_neverCalledByScheduler() {
            // The scheduler itself must NOT call stampAlertSentAt — that responsibility
            // now belongs exclusively to ExpiryEvaluationService (RFC 0008 §2 / §4).
            Target target = enabledTarget();
            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of(certExpiring(target, 10)));
            when(expiryEvaluationService.evaluateAndNotify(anyCollection(), any())).thenReturn(1);

            scheduler.checkExpiringCertificates();

            verify(certRepository, never()).stampAlertSentAt(any(), any());
        }
    }

    @Nested
    class SkipCriteria {

        @Test
        void disabledTarget_skipped_noEvaluationServiceInteraction() {
            // Disabled targets are filtered at the JPQL level; simulate empty result.
            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of());

            scheduler.checkExpiringCertificates();

            // resolveMaxWarningDays() is called to build the window but evaluateAndNotify
            // must never be reached when the candidate set is empty.
            verify(expiryEvaluationService, never()).evaluateAndNotify(anyCollection(), any());
        }
    }
}
