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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExpiryEvaluationService (RFC 0008 §2).
 *
 * Transaction synchronisation: TransactionSynchronizationManager is static and
 * thread-local. In tests there is no active transaction, so the service falls
 * through to a direct dispatchExpiryAlert call (the fallback branch) rather than
 * registering an AFTER_COMMIT listener. This lets us verify dispatch with plain
 * Mockito without spinning up a full Spring context.
 */
@ExtendWith(MockitoExtension.class)
class ExpiryEvaluationServiceTest {

    @Mock NotificationService notificationService;
    @Mock CertificateRecordRepository certRepository;

    ExpiryEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new ExpiryEvaluationService(notificationService, certRepository);
        ReflectionTestUtils.setField(service, "warningDays", 30);
        ReflectionTestUtils.setField(service, "criticalDays", 7);
        ReflectionTestUtils.setField(service, "dedupHours", 23);
        ReflectionTestUtils.setField(service, "forceScanDebounceSeconds", 120);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Organization buildOrg() {
        Organization org = Organization.builder().name("TestOrg").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        return org;
    }

    private Target enabledTarget(Organization org) {
        return Target.builder()
                .organization(org).host("example.com").port(443).enabled(true).build();
    }

    private CertificateRecord certExpiring(Target target, int daysFromNow) {
        return certExpiring(target, daysFromNow, null);
    }

    private CertificateRecord certExpiring(Target target, int daysFromNow, Instant lastAlertSentAt) {
        CertificateRecord cert = CertificateRecord.builder()
                .target(target)
                .orgId(target.getOrganization().getId())
                .commonName("example.com")
                .issuer("Test CA")
                .serialNumber("ABC123")
                .expiryDate(Instant.now().plus(daysFromNow, ChronoUnit.DAYS))
                .notBefore(Instant.now().minus(30, ChronoUnit.DAYS))
                .build();
        ReflectionTestUtils.setField(cert, "id", UUID.randomUUID());
        cert.setLastAlertSentAt(lastAlertSentAt);
        return cert;
    }

    // ── Not-in-window ─────────────────────────────────────────────────────────

    @Nested
    class NotInWindow {

        @Test
        void certExpiringBeyondWarningDays_noStampNoDispatch() {
            Target target = enabledTarget(buildOrg());
            // Use 60 days — well beyond warningDays=30 so DAYS.between always gives > 30
            // even accounting for sub-second elapsed time between cert creation and evaluation.
            CertificateRecord cert = certExpiring(target, 60);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verifyNoInteractions(certRepository);
            verifyNoInteractions(notificationService);
        }

        @Test
        void batchForm_certsBeyondWindow_returnsZeroDispatched() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord c1 = certExpiring(target, 60);
            CertificateRecord c2 = certExpiring(target, 90);

            int count = service.evaluateAndNotify(
                    List.of(c1, c2), ExpiryEvaluationService.EvaluationMode.SCHEDULED);

            assertThat(count).isZero();
            verifyNoInteractions(certRepository);
            verifyNoInteractions(notificationService);
        }
    }

    // ── SCHEDULED mode ────────────────────────────────────────────────────────

    @Nested
    class ScheduledMode {

        @Test
        void inWindow_neverAlerted_stampsAndDispatches() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 20, null);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), eq("WARNING"));
        }

        @Test
        void inWindow_alertedOutsideDedupWindow_stampsAndDispatches() {
            Target target = enabledTarget(buildOrg());
            Instant oldAlert = Instant.now().minus(25, ChronoUnit.HOURS); // > 23h dedup
            CertificateRecord cert = certExpiring(target, 20, oldAlert);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), eq("WARNING"));
        }

        @Test
        void inWindow_alertedWithinDedupWindow_noStampNoDispatch() {
            Target target = enabledTarget(buildOrg());
            Instant recentAlert = Instant.now().minus(1, ChronoUnit.HOURS); // within 23h dedup
            CertificateRecord cert = certExpiring(target, 20, recentAlert);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(certRepository, never()).stampAlertSentAt(any(), any());
            verifyNoInteractions(notificationService);
        }

        @Test
        void criticalWindow_dispatchesCritical() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 5, null); // <= criticalDays=7

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), eq("CRITICAL"));
        }

        @Test
        void warningBoundary_exactly30days_dispatchesWarning() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 30, null);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), eq("WARNING"));
        }

        @Test
        void alreadyExpired_negativeDays_dispatchesCritical() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, -5, null);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(notificationService).dispatchExpiryAlert(eq(cert), intThat(d -> d < 0), eq("CRITICAL"));
        }

        @Test
        void batch_mixedWindow_returnsCorrectDispatchCount() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord inWindow  = certExpiring(target, 20, null);
            CertificateRecord outWindow = certExpiring(target, 60, null);
            // dedup-suppressed
            Instant recent = Instant.now().minus(2, ChronoUnit.HOURS);
            CertificateRecord deduped = certExpiring(target, 15, recent);

            int count = service.evaluateAndNotify(
                    List.of(inWindow, outWindow, deduped),
                    ExpiryEvaluationService.EvaluationMode.SCHEDULED);

            assertThat(count).isEqualTo(1); // only inWindow
            verify(certRepository, times(1)).stampAlertSentAt(any(), any());
            verify(notificationService, times(1)).dispatchExpiryAlert(any(), anyInt(), anyString());
        }

        @Test
        void stampCalledWithTimestampWithinTestBounds() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 10, null);

            Instant before = Instant.now().minusSeconds(1);
            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);
            Instant after = Instant.now().plusSeconds(1);

            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(certRepository).stampAlertSentAt(eq(cert.getId()), captor.capture());
            assertThat(captor.getValue()).isAfter(before).isBefore(after);
        }
    }

    // ── FORCE mode ─────────────────────────────────────────────────────────────

    @Nested
    class ForceMode {

        @Test
        void force_bypassesDedupGate_alertedWithinWindow_stillDispatches() {
            Target target = enabledTarget(buildOrg());
            // cert was alerted 1 hour ago — SCHEDULED would skip this
            Instant recentAlert = Instant.now().minus(1, ChronoUnit.HOURS);
            CertificateRecord cert = certExpiring(target, 20, recentAlert);

            // previousLastScannedAt is old enough that debounce doesn't fire
            Instant oldScan = Instant.now().minus(300, ChronoUnit.SECONDS);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.FORCE, oldScan);

            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), anyString());
        }

        @Test
        void force_stampsEvenWhenDedupWouldHaveSkipped() {
            // Confirm the stamp is always written in FORCE mode (so a later SCHEDULED
            // sweep correctly sees the fresh lastAlertSentAt and dedups against it).
            Target target = enabledTarget(buildOrg());
            Instant veryRecentAlert = Instant.now().minus(30, ChronoUnit.MINUTES);
            CertificateRecord cert = certExpiring(target, 10, veryRecentAlert);
            Instant oldScan = Instant.now().minus(300, ChronoUnit.SECONDS);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.FORCE, oldScan);

            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
        }

        @Test
        void force_debounce_suppressesWhenTargetScannedRecently() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 20, null);
            // previous scan was only 30 seconds ago — within 120s debounce window
            Instant recentScan = Instant.now().minus(30, ChronoUnit.SECONDS);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.FORCE, recentScan);

            verify(certRepository, never()).stampAlertSentAt(any(), any());
            verifyNoInteractions(notificationService);
        }

        @Test
        void force_debounce_firesWhenTargetScannedBeyondDebounceWindow() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 20, null);
            // previous scan was 300 seconds ago — beyond 120s debounce
            Instant oldScan = Instant.now().minus(300, ChronoUnit.SECONDS);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.FORCE, oldScan);

            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), anyString());
        }

        @Test
        void force_nullPreviousLastScannedAt_debounceNotActive_dispatches() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 20, null);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.FORCE, null);

            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), anyString());
        }
    }

    // ── AFTER_COMMIT dispatch wiring ──────────────────────────────────────────

    @Nested
    class AfterCommitDispatch {

        /**
         * Verifies that when a real Spring transaction synchronisation is active,
         * the service registers an AFTER_COMMIT callback rather than dispatching
         * inline. The callback, when invoked, calls dispatchExpiryAlert.
         *
         * We simulate an active synchronisation context manually (TransactionSynchronizationManager
         * is just a ThreadLocal) and verify the synchronization is registered.
         */
        @Test
        void withActiveSynchronization_registersAfterCommitCallback() {
            TransactionSynchronizationManager.initSynchronization();
            try {
                Target target = enabledTarget(buildOrg());
                CertificateRecord cert = certExpiring(target, 10, null);

                service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

                // A synchronization should have been registered (dispatch is deferred).
                assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
                // NotificationService should NOT have been called yet (deferred to after-commit).
                verifyNoInteractions(notificationService);

                // Simulate commit — invoke the registered after-commit callback.
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(s -> s.afterCommit());

                verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), anyString());
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        void withoutActiveSynchronization_dispatchesDirectly() {
            // Default test context: no active synchronization → direct dispatch fallback.
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 10, null);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            // Immediate dispatch because no transaction is active.
            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), anyString());
        }
    }

    // ── determineCertStatus ───────────────────────────────────────────────────

    @Nested
    class DetermineCertStatus {

        @Test
        void expired_returnsExpired() {
            Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
            assertThat(service.determineCertStatus(past))
                    .isEqualTo(com.certguard.enums.CertStatus.EXPIRED);
        }

        @Test
        void withinWarningDays_returnsExpiring() {
            Instant soon = Instant.now().plus(15, ChronoUnit.DAYS); // <= warningDays=30
            assertThat(service.determineCertStatus(soon))
                    .isEqualTo(com.certguard.enums.CertStatus.EXPIRING);
        }

        @Test
        void beyondWarningDays_returnsValid() {
            Instant far = Instant.now().plus(60, ChronoUnit.DAYS); // > warningDays=30
            assertThat(service.determineCertStatus(far))
                    .isEqualTo(com.certguard.enums.CertStatus.VALID);
        }
    }
}
