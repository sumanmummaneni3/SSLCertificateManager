package com.certguard.service;

import com.certguard.entity.CertificateRecord;
import com.certguard.entity.NotificationSettings;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.enums.CertStatus;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.repository.NotificationSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExpiryEvaluationService (RFC 0008 §2 + §3).
 *
 * Transaction synchronisation: TransactionSynchronizationManager is static and
 * thread-local. In tests there is no active transaction, so the service falls
 * through to a direct dispatchExpiryAlert call (the fallback branch) rather than
 * registering an AFTER_COMMIT listener. This lets us verify dispatch with plain
 * Mockito without spinning up a full Spring context.
 *
 * Settings resolution: by default the settingsRepository mocks return empty
 * Optionals/Lists, so the app-yml fallback values (set via ReflectionTestUtils)
 * apply. Individual tests wire specific mocked rows to verify the resolution chain.
 */
@ExtendWith(MockitoExtension.class)
class ExpiryEvaluationServiceTest {

    @Mock NotificationService notificationService;
    @Mock CertificateRecordRepository certRepository;
    @Mock NotificationSettingsRepository settingsRepository;

    ExpiryEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new ExpiryEvaluationService(notificationService, certRepository, settingsRepository);
        ReflectionTestUtils.setField(service, "warningDays", 30);
        ReflectionTestUtils.setField(service, "criticalDays", 7);
        ReflectionTestUtils.setField(service, "dedupHours", 23);
        ReflectionTestUtils.setField(service, "forceScanDebounceSeconds", 120);

        // Default: no persisted settings rows — fallback to app-yml values.
        lenient().when(settingsRepository.findByTargetId(any())).thenReturn(Optional.empty());
        lenient().when(settingsRepository.findByOrganizationIdAndTargetIsNull(any())).thenReturn(Optional.empty());
        lenient().when(settingsRepository.findByTargetIdIn(anyCollection())).thenReturn(List.of());
        lenient().when(settingsRepository.findByOrgIdInAndTargetIsNull(anyCollection())).thenReturn(List.of());
        lenient().when(settingsRepository.findMaxWarningDays()).thenReturn(Optional.empty());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Organization buildOrg() {
        Organization org = Organization.builder().name("TestOrg").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        return org;
    }

    private Target enabledTarget(Organization org) {
        Target t = Target.builder()
                .organization(org).host("example.com").port(443).enabled(true).build();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        return t;
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

    private NotificationSettings buildSettings(Organization org, Target target,
                                               boolean enabled, int warning, int critical, int dedup) {
        NotificationSettings ns = NotificationSettings.builder()
                .organization(org)
                .target(target)
                .enabled(enabled)
                .warningDays(warning)
                .criticalDays(critical)
                .dedupHours(dedup)
                .build();
        ReflectionTestUtils.setField(ns, "id", UUID.randomUUID());
        return ns;
    }

    // ── Settings resolution chain (RFC 0008 §3.2) ─────────────────────────────

    @Nested
    class ResolutionChain {

        @Test
        void perTargetOverride_usedWhenPresent() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            CertificateRecord cert = certExpiring(target, 20, null);
            // Override has warningDays=45 — if used, cert (20 days) is in-window.
            // App fallback has warningDays=30 — cert is also in-window, but we verify
            // the override's dedupHours (1h) is used by checking it doesn't dedup.
            // For isolation, give the override a very short dedup window (1h) and pre-alert 2h ago.
            Instant alertedLong = Instant.now().minus(2, ChronoUnit.HOURS);
            cert.setLastAlertSentAt(alertedLong);

            NotificationSettings override = buildSettings(org, target, true, 45, 7, 1);
            when(settingsRepository.findByTargetId(target.getId())).thenReturn(Optional.of(override));

            // 2h ago > 1h dedup → should dispatch (override's dedupHours=1 applies)
            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), anyString());
        }

        @Test
        void orgDefaultUsed_whenNoPerTargetOverride() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            CertificateRecord cert = certExpiring(target, 20, null);
            // Org default has enabled=false → no dispatch.
            NotificationSettings orgDefault = buildSettings(org, null, false, 30, 7, 23);
            when(settingsRepository.findByOrganizationIdAndTargetIsNull(org.getId()))
                    .thenReturn(Optional.of(orgDefault));

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verifyNoInteractions(notificationService);
            verify(certRepository, never()).stampAlertSentAt(any(), any());
        }

        @Test
        void perTargetBeatsOrgDefault() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            CertificateRecord cert = certExpiring(target, 20, null);

            // Org default is disabled — per-target override is enabled.
            NotificationSettings override = buildSettings(org, target, true, 30, 7, 23);
            NotificationSettings orgDefault = buildSettings(org, null, false, 30, 7, 23);

            when(settingsRepository.findByTargetId(target.getId())).thenReturn(Optional.of(override));
            // Lenient: per-target is found first so this org-default stub will not be called.
            // It's wired here to document the intended test invariant, not to be exercised.
            lenient().when(settingsRepository.findByOrganizationIdAndTargetIsNull(org.getId()))
                    .thenReturn(Optional.of(orgDefault));

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            // Per-target wins → enabled=true → dispatches.
            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), anyString());
        }

        @Test
        void appYmlFallback_whenNoRows() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            CertificateRecord cert = certExpiring(target, 20, null);
            // Both repo calls return empty — fallback (warningDays=30) applies.
            // 20 days < 30 → in-window → dispatches.
            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), anyString());
        }

        @Test
        void batchForm_usesPreloadedMapsNotPerCertQueries() {
            Organization org = buildOrg();
            Target t1 = enabledTarget(org);
            Target t2 = enabledTarget(org);
            CertificateRecord c1 = certExpiring(t1, 10, null);
            CertificateRecord c2 = certExpiring(t2, 15, null);

            // Pre-loaded maps return empty → fallback applies to all certs.
            when(settingsRepository.findByTargetIdIn(anyCollection())).thenReturn(List.of());
            when(settingsRepository.findByOrgIdInAndTargetIsNull(anyCollection())).thenReturn(List.of());

            service.evaluateAndNotify(List.of(c1, c2), ExpiryEvaluationService.EvaluationMode.SCHEDULED);

            // Two batch queries — NOT per-cert findByTargetId / findByOrganizationId calls.
            verify(settingsRepository, times(1)).findByTargetIdIn(anyCollection());
            verify(settingsRepository, times(1)).findByOrgIdInAndTargetIsNull(anyCollection());
            verify(settingsRepository, never()).findByTargetId(any());
            verify(settingsRepository, never()).findByOrganizationIdAndTargetIsNull(any());
        }
    }

    // ── resolveMaxWarningDays ─────────────────────────────────────────────────

    @Nested
    class ResolveMaxWarningDays {

        @Test
        void noRows_returnsAppYmlDefault() {
            when(settingsRepository.findMaxWarningDays()).thenReturn(Optional.empty());
            assertThat(service.resolveMaxWarningDays()).isEqualTo(30);
        }

        @Test
        void rowsPresent_returnsMaxOfDbAndAppYml() {
            when(settingsRepository.findMaxWarningDays()).thenReturn(Optional.of(60));
            assertThat(service.resolveMaxWarningDays()).isEqualTo(60);
        }

        @Test
        void rowsPresent_butSmallerThanAppYml_returnsAppYml() {
            when(settingsRepository.findMaxWarningDays()).thenReturn(Optional.of(10));
            assertThat(service.resolveMaxWarningDays()).isEqualTo(30); // max(10,30)=30
        }
    }

    // ── Not-in-window ─────────────────────────────────────────────────────────

    @Nested
    class NotInWindow {

        @Test
        void certExpiringBeyondWarningDays_noStampNoDispatch() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 60); // well beyond warningDays=30

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(certRepository, never()).stampAlertSentAt(any(), any());
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
            verify(certRepository, never()).stampAlertSentAt(any(), any());
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
            Instant recentAlert = Instant.now().minus(1, ChronoUnit.HOURS);
            CertificateRecord cert = certExpiring(target, 20, recentAlert);
            Instant oldScan = Instant.now().minus(300, ChronoUnit.SECONDS);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.FORCE, oldScan);

            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), anyString());
        }

        @Test
        void force_stampsEvenWhenDedupWouldHaveSkipped() {
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
            Instant recentScan = Instant.now().minus(30, ChronoUnit.SECONDS); // within 120s

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.FORCE, recentScan);

            verify(certRepository, never()).stampAlertSentAt(any(), any());
            verifyNoInteractions(notificationService);
        }

        @Test
        void force_debounce_firesWhenTargetScannedBeyondDebounceWindow() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 20, null);
            Instant oldScan = Instant.now().minus(300, ChronoUnit.SECONDS); // beyond 120s

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

        @Test
        void withActiveSynchronization_registersAfterCommitCallback() {
            TransactionSynchronizationManager.initSynchronization();
            try {
                Target target = enabledTarget(buildOrg());
                CertificateRecord cert = certExpiring(target, 10, null);

                service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

                assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
                verifyNoInteractions(notificationService);

                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(s -> s.afterCommit());

                verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), anyString());
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        void withoutActiveSynchronization_dispatchesDirectly() {
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 10, null);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(notificationService).dispatchExpiryAlert(eq(cert), anyInt(), anyString());
        }
    }

    // ── determineCertStatus ───────────────────────────────────────────────────

    @Nested
    class DetermineCertStatusTests {

        @Test
        void expired_returnsExpired() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
            assertThat(service.determineCertStatus(past, target, org.getId()))
                    .isEqualTo(CertStatus.EXPIRED);
        }

        @Test
        void withinWarningDays_returnsExpiring() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            Instant soon = Instant.now().plus(15, ChronoUnit.DAYS); // <= warningDays=30
            assertThat(service.determineCertStatus(soon, target, org.getId()))
                    .isEqualTo(CertStatus.EXPIRING);
        }

        @Test
        void beyondWarningDays_returnsValid() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            Instant far = Instant.now().plus(60, ChronoUnit.DAYS); // > warningDays=30
            assertThat(service.determineCertStatus(far, target, org.getId()))
                    .isEqualTo(CertStatus.VALID);
        }

        @Test
        void perTargetOverrideWarningDays_usedForStatusDerivation() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            // Override has warningDays=60 — a cert expiring in 45 days should be EXPIRING.
            NotificationSettings override = buildSettings(org, target, true, 60, 7, 23);
            when(settingsRepository.findByTargetId(target.getId())).thenReturn(Optional.of(override));

            Instant in45Days = Instant.now().plus(45, ChronoUnit.DAYS);
            assertThat(service.determineCertStatus(in45Days, target, org.getId()))
                    .isEqualTo(CertStatus.EXPIRING);
        }
    }
}
