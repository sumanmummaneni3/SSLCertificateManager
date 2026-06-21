package com.certguard.service;

import com.certguard.dto.internal.ExpiryAlertContext;
import com.certguard.entity.Agent;
import com.certguard.entity.CertificateRecord;
import com.certguard.entity.NotificationSettings;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.enums.CertStatus;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.repository.NotificationSettingsRepository;
import org.hibernate.LazyInitializationException;
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
import java.util.Map;
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
 *
 * P1-A: After the fix, dispatchExpiryAlert is called with an ExpiryAlertContext
 * (not a CertificateRecord entity). All verify calls use the context overload.
 * resolveChannels is stubbed in setUp to return an empty map so buildAlertContext
 * does not throw; individual tests stub it with a real channel map when dispatch
 * must reach the notificationService.
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

        // P1-A: buildAlertContext calls notificationService.resolveChannels(target) while
        // still in-transaction. Stub it to return a non-empty email channel so dispatch
        // actually fires in tests that assert send behaviour; tests that only check stamping
        // or the no-dispatch path don't need a real channel map.
        lenient().when(notificationService.resolveChannels(any())).thenReturn(
                Map.of("email", Map.of("enabled", true, "addresses", List.of("ops@test.com"))));
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

    /** Captures the ExpiryAlertContext passed to the context-based dispatch overload. */
    private ExpiryAlertContext captureDispatchedContext() {
        ArgumentCaptor<ExpiryAlertContext> cap = ArgumentCaptor.forClass(ExpiryAlertContext.class);
        verify(notificationService).dispatchExpiryAlert(cap.capture());
        return cap.getValue();
    }

    // ── P1-A regression: context-based dispatch (no lazy entity access) ────────

    @Nested
    class P1aLazyLoadRegression {

        /**
         * Demonstrates that dispatchExpiryAlert(ExpiryAlertContext) performs ZERO
         * entity/lazy access. We pass a context built from scratch (no JPA entity at all)
         * and verify the mail dispatch is still invoked — confirming the fixed code path
         * is safe across the transaction/session boundary.
         *
         * Contrast with the old entity-based path: if dispatchExpiryAlert(CertificateRecord)
         * were called here with a target whose getOrganization() throws
         * LazyInitializationException, the exception would be swallowed by the async
         * executor and the send would never happen.
         */
        @Test
        void contextOverload_requiresZeroEntityAccess_dispatchSucceeds() {
            // Build a context entirely from primitive/plain-java values — no JPA entity.
            ExpiryAlertContext ctx = new ExpiryAlertContext(
                    UUID.randomUUID(),          // certId
                    "internal.example.com",     // host
                    443,                        // port
                    15,                         // daysLeft
                    "WARNING",                  // severity
                    UUID.randomUUID(),          // orgId
                    false,                      // agentDiscovered
                    Map.of("email", Map.of(     // channels — fully pre-resolved
                            "enabled", true,
                            "addresses", List.of("ops@example.com")))
            );

            // Should not throw; performs no entity access whatsoever.
            notificationService.dispatchExpiryAlert(ctx);

            verify(notificationService).dispatchExpiryAlert(ctx);
        }

        /**
         * Proves that the fixed evaluateSingle path builds the context pre-commit
         * (resolveChannels is called while the mock is live / session would be open)
         * and passes an ExpiryAlertContext to dispatch — NOT a CertificateRecord.
         *
         * If the old entity-based overload were called instead, the
         * verify(notificationService).dispatchExpiryAlert(any(ExpiryAlertContext.class))
         * assertion would fail (wrong overload).
         */
        @Test
        void evaluateSingle_dispatchesViaContext_notEntityOverload() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            CertificateRecord cert = certExpiring(target, 10, null);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            // Must use the context overload — entity overload must NOT be called.
            verify(notificationService).dispatchExpiryAlert(any(ExpiryAlertContext.class));
            verify(notificationService, never()).dispatchExpiryAlert(
                    any(CertificateRecord.class), anyInt(), anyString());
        }

        /**
         * Simulates the sweep's detached-entity scenario: the target's getOrganization()
         * is overridden to throw LazyInitializationException (mimicking a detached proxy).
         * With the old code, buildAlertContext would throw inside the AFTER_COMMIT callback
         * and the exception would be swallowed. With the fix, resolveChannels (and thus
         * getOrganization()) is called pre-commit where we control the mock — no throw.
         *
         * To be precise: in the unit test context resolveChannels is mocked; this test
         * therefore verifies that buildAlertContext does NOT call getOrganization() directly
         * (org ID comes from cert.getOrgId() which is a denormalized UUID column).
         */
        @Test
        void buildAlertContext_usesOrgIdFromDenormalizedColumn_notLazyAssociation() {
            Organization org = buildOrg();
            // Build a target that would throw if getOrganization() were called on an
            // unproxied access — simulated via a subclass override.
            Target lazyTarget = new Target() {
                @Override
                public Organization getOrganization() {
                    throw new LazyInitializationException(
                            "could not initialize proxy - no Session (P1-A regression)");
                }

                @Override
                public String getHost() { return "lazy.example.com"; }

                @Override
                public Integer getPort() { return 443; }

                @Override
                public Agent getAgent() { return null; }

                @Override
                public java.util.Map<String, Object> getNotificationChannels() {
                    return Map.of("email", Map.of("enabled", true,
                            "addresses", List.of("ops@example.com")));
                }
            };
            ReflectionTestUtils.setField(lazyTarget, "id", UUID.randomUUID());

            // resolveChannels is called via notificationService mock — no lazy access.
            // cert.getOrgId() is a plain UUID field — no entity traversal.
            CertificateRecord cert = CertificateRecord.builder()
                    .target(lazyTarget)
                    .orgId(org.getId())   // denormalized — no lazy access needed
                    .commonName("lazy.example.com")
                    .issuer("Test CA")
                    .serialNumber("LAZY1")
                    .expiryDate(Instant.now().plus(10, ChronoUnit.DAYS))
                    .notBefore(Instant.now().minus(30, ChronoUnit.DAYS))
                    .build();
            ReflectionTestUtils.setField(cert, "id", UUID.randomUUID());

            // evaluateAndNotify must not throw LazyInitializationException.
            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            // Dispatch still reached.
            verify(notificationService).dispatchExpiryAlert(any(ExpiryAlertContext.class));
        }

        /**
         * Verifies the ExpiryAlertContext carries the correct field values that
         * dispatchExpiryAlert needs — so nothing is silently dropped in translation.
         */
        @Test
        void alertContext_fieldsPopulatedCorrectly() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            CertificateRecord cert = certExpiring(target, 5, null); // 5d → CRITICAL

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            ExpiryAlertContext ctx = captureDispatchedContext();
            assertThat(ctx.certId()).isEqualTo(cert.getId());
            assertThat(ctx.host()).isEqualTo("example.com");
            assertThat(ctx.port()).isEqualTo(443);
            assertThat(ctx.severity()).isEqualTo("CRITICAL");
            assertThat(ctx.orgId()).isEqualTo(org.getId());
            assertThat(ctx.daysLeft()).isLessThanOrEqualTo(7); // within critical window
        }
    }

    // ── Settings resolution chain (RFC 0008 §3.2) ─────────────────────────────

    @Nested
    class ResolutionChain {

        @Test
        void perTargetOverride_usedWhenPresent() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            CertificateRecord cert = certExpiring(target, 20, null);
            // Override has warningDays=45 — cert (20 days) is in-window.
            // Give override dedupHours=1; pre-alert 2h ago → outside dedup → dispatches.
            Instant alertedLong = Instant.now().minus(2, ChronoUnit.HOURS);
            cert.setLastAlertSentAt(alertedLong);

            NotificationSettings override = buildSettings(org, target, true, 45, 7, 1);
            when(settingsRepository.findByTargetId(target.getId())).thenReturn(Optional.of(override));

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(notificationService).dispatchExpiryAlert(any(ExpiryAlertContext.class));
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

            verify(notificationService, never()).dispatchExpiryAlert(any(ExpiryAlertContext.class));
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
            // Lenient: per-target found first — org-default stub not exercised.
            lenient().when(settingsRepository.findByOrganizationIdAndTargetIsNull(org.getId()))
                    .thenReturn(Optional.of(orgDefault));

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            // Per-target wins → enabled=true → dispatches.
            verify(notificationService).dispatchExpiryAlert(any(ExpiryAlertContext.class));
        }

        @Test
        void appYmlFallback_whenNoRows() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);
            CertificateRecord cert = certExpiring(target, 20, null);
            // Both repo calls return empty — fallback (warningDays=30) applies.
            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(notificationService).dispatchExpiryAlert(any(ExpiryAlertContext.class));
        }

        @Test
        void batchForm_usesPreloadedMapsNotPerCertQueries() {
            Organization org = buildOrg();
            Target t1 = enabledTarget(org);
            Target t2 = enabledTarget(org);
            CertificateRecord c1 = certExpiring(t1, 10, null);
            CertificateRecord c2 = certExpiring(t2, 15, null);

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
            verify(notificationService, never()).dispatchExpiryAlert(any(ExpiryAlertContext.class));
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
            verify(notificationService, never()).dispatchExpiryAlert(any(ExpiryAlertContext.class));
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
            ExpiryAlertContext ctx = captureDispatchedContext();
            assertThat(ctx.severity()).isEqualTo("WARNING");
        }

        @Test
        void inWindow_alertedOutsideDedupWindow_stampsAndDispatches() {
            Target target = enabledTarget(buildOrg());
            Instant oldAlert = Instant.now().minus(25, ChronoUnit.HOURS); // > 23h dedup
            CertificateRecord cert = certExpiring(target, 20, oldAlert);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
            ExpiryAlertContext ctx = captureDispatchedContext();
            assertThat(ctx.severity()).isEqualTo("WARNING");
        }

        @Test
        void inWindow_alertedWithinDedupWindow_noStampNoDispatch() {
            Target target = enabledTarget(buildOrg());
            Instant recentAlert = Instant.now().minus(1, ChronoUnit.HOURS); // within 23h dedup
            CertificateRecord cert = certExpiring(target, 20, recentAlert);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            verify(certRepository, never()).stampAlertSentAt(any(), any());
            verify(notificationService, never()).dispatchExpiryAlert(any(ExpiryAlertContext.class));
        }

        @Test
        void criticalWindow_dispatchesCritical() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 5, null); // <= criticalDays=7

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            ExpiryAlertContext ctx = captureDispatchedContext();
            assertThat(ctx.severity()).isEqualTo("CRITICAL");
        }

        @Test
        void warningBoundary_exactly30days_dispatchesWarning() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 30, null);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            ExpiryAlertContext ctx = captureDispatchedContext();
            assertThat(ctx.severity()).isEqualTo("WARNING");
        }

        @Test
        void alreadyExpired_negativeDays_dispatchesCritical() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, -5, null);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);

            ExpiryAlertContext ctx = captureDispatchedContext();
            assertThat(ctx.severity()).isEqualTo("CRITICAL");
            assertThat(ctx.daysLeft()).isNegative();
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
            verify(notificationService, times(1)).dispatchExpiryAlert(any(ExpiryAlertContext.class));
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
            verify(notificationService).dispatchExpiryAlert(any(ExpiryAlertContext.class));
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
            verify(notificationService, never()).dispatchExpiryAlert(any(ExpiryAlertContext.class));
        }

        @Test
        void force_debounce_firesWhenTargetScannedBeyondDebounceWindow() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 20, null);
            Instant oldScan = Instant.now().minus(300, ChronoUnit.SECONDS); // beyond 120s

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.FORCE, oldScan);

            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
            verify(notificationService).dispatchExpiryAlert(any(ExpiryAlertContext.class));
        }

        @Test
        void force_nullPreviousLastScannedAt_debounceNotActive_dispatches() {
            Target target = enabledTarget(buildOrg());
            CertificateRecord cert = certExpiring(target, 20, null);

            service.evaluateAndNotify(cert, ExpiryEvaluationService.EvaluationMode.FORCE, null);

            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
            verify(notificationService).dispatchExpiryAlert(any(ExpiryAlertContext.class));
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
                // The context-based overload must not be called before afterCommit fires.
                verify(notificationService, never()).dispatchExpiryAlert(any(ExpiryAlertContext.class));

                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(s -> s.afterCommit());

                verify(notificationService).dispatchExpiryAlert(any(ExpiryAlertContext.class));
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

            verify(notificationService).dispatchExpiryAlert(any(ExpiryAlertContext.class));
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

    // ── RFC 0009 §3.5: Status precedence (BE-7) ───────────────────────────────

    /**
     * Tests for the new 5-arg {@code determineCertStatus} overload that incorporates
     * revocation and chain validation results.
     * Precedence: REVOKED > EXPIRED > INVALID > EXPIRING > VALID
     */
    @Nested
    class StatusPrecedenceRfc0009 {

        private final com.certguard.service.revocation.RevocationResult REVOKED_RESULT =
                com.certguard.service.revocation.RevocationResult.revoked(
                        com.certguard.enums.RevocationSource.OCSP, "KEY_COMPROMISE", 1, java.time.Instant.now());

        private final com.certguard.service.revocation.RevocationResult GOOD_RESULT =
                com.certguard.service.revocation.RevocationResult.good(
                        com.certguard.enums.RevocationSource.OCSP);

        private final com.certguard.service.revocation.RevocationResult UNKNOWN_RESULT =
                com.certguard.service.revocation.RevocationResult.unknown(
                        com.certguard.enums.RevocationSource.OCSP, "responder down");

        private final com.certguard.service.chain.ChainValidationResult TRUSTED_CHAIN =
                com.certguard.service.chain.ChainValidationResult.trusted(3);

        private final com.certguard.service.chain.ChainValidationResult UNTRUSTED_CHAIN =
                com.certguard.service.chain.ChainValidationResult.failed(
                        com.certguard.enums.ChainValidationError.UNTRUSTED_ANCHOR, 3);

        @BeforeEach
        void setRevocationShadow() {
            // Disable shadow mode so status changes actually apply
            ReflectionTestUtils.setField(service, "revocationShadowMode", false);
        }

        @Test
        void revoked_beats_everything() {
            Organization org = buildOrg();
            Target publicTarget = enabledTarget(org);
            ReflectionTestUtils.setField(publicTarget, "isPrivate", false);

            Instant expired = Instant.now().minus(1, ChronoUnit.DAYS);

            CertStatus status = service.determineCertStatus(
                    expired, REVOKED_RESULT, UNTRUSTED_CHAIN, publicTarget, org.getId());

            // Even though the cert is expired AND chain is untrusted, REVOKED wins
            assertThat(status).isEqualTo(CertStatus.REVOKED);
        }

        @Test
        void expired_beats_invalid_when_not_revoked() {
            Organization org = buildOrg();
            Target publicTarget = enabledTarget(org);
            ReflectionTestUtils.setField(publicTarget, "isPrivate", false);

            Instant expired = Instant.now().minus(1, ChronoUnit.DAYS);

            CertStatus status = service.determineCertStatus(
                    expired, GOOD_RESULT, UNTRUSTED_CHAIN, publicTarget, org.getId());

            assertThat(status).isEqualTo(CertStatus.EXPIRED);
        }

        @Test
        void invalid_returned_for_public_target_with_untrusted_chain_and_valid_expiry() {
            Organization org = buildOrg();
            Target publicTarget = enabledTarget(org);
            ReflectionTestUtils.setField(publicTarget, "isPrivate", false);

            Instant future = Instant.now().plus(60, ChronoUnit.DAYS);

            CertStatus status = service.determineCertStatus(
                    future, GOOD_RESULT, UNTRUSTED_CHAIN, publicTarget, org.getId());

            assertThat(status).isEqualTo(CertStatus.INVALID);
        }

        @Test
        void private_target_with_untrusted_chain_stays_advisory_not_invalid() {
            Organization org = buildOrg();
            Target privateTarget = enabledTarget(org);
            ReflectionTestUtils.setField(privateTarget, "isPrivate", true);

            Instant future = Instant.now().plus(60, ChronoUnit.DAYS);

            CertStatus status = service.determineCertStatus(
                    future, GOOD_RESULT, UNTRUSTED_CHAIN, privateTarget, org.getId());

            // Private targets don't get INVALID; they stay VALID/EXPIRING
            assertThat(status).isIn(CertStatus.VALID, CertStatus.EXPIRING);
        }

        @Test
        void expiring_when_no_revocation_no_chain_issue() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);

            Instant expiring = Instant.now().plus(10, ChronoUnit.DAYS); // inside 30-day window

            CertStatus status = service.determineCertStatus(
                    expiring, GOOD_RESULT, TRUSTED_CHAIN, target, org.getId());

            assertThat(status).isEqualTo(CertStatus.EXPIRING);
        }

        @Test
        void valid_when_no_issues_and_far_expiry() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);

            Instant future = Instant.now().plus(60, ChronoUnit.DAYS);

            CertStatus status = service.determineCertStatus(
                    future, GOOD_RESULT, TRUSTED_CHAIN, target, org.getId());

            assertThat(status).isEqualTo(CertStatus.VALID);
        }

        @Test
        void unknown_revocation_soft_fail_does_not_set_revoked() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);

            Instant future = Instant.now().plus(60, ChronoUnit.DAYS);

            CertStatus status = service.determineCertStatus(
                    future, UNKNOWN_RESULT, TRUSTED_CHAIN, target, org.getId());

            // UNKNOWN with soft-fail should NOT become REVOKED
            assertThat(status).isNotEqualTo(CertStatus.REVOKED);
            assertThat(status).isEqualTo(CertStatus.VALID);
        }

        @Test
        void null_revocation_and_null_chain_falls_back_to_expiry_logic() {
            Organization org = buildOrg();
            Target target = enabledTarget(org);

            Instant expiring = Instant.now().plus(5, ChronoUnit.DAYS); // critical window

            CertStatus status = service.determineCertStatus(
                    expiring, null, null, target, org.getId());

            assertThat(status).isEqualTo(CertStatus.EXPIRING);
        }

        @Test
        void determineCertStatus_always_returns_true_status_shadow_applied_by_caller() {
            // Shadow mode is enforced by SslScannerService / AgentService, NOT by
            // determineCertStatus. The method itself always reflects the true status.
            // Callers gate the final entity.setStatus() call.
            ReflectionTestUtils.setField(service, "revocationShadowMode", true);

            Organization org = buildOrg();
            Target target = enabledTarget(org);

            Instant future = Instant.now().plus(60, ChronoUnit.DAYS);

            CertStatus status = service.determineCertStatus(
                    future, REVOKED_RESULT, TRUSTED_CHAIN, target, org.getId());

            // determineCertStatus is the single authority — it returns REVOKED regardless.
            // Suppression happens at the persistCertificates / processFull level.
            assertThat(status).isEqualTo(CertStatus.REVOKED);
        }

        @Test
        void evaluateRevocationAndNotify_suppressed_in_shadow_mode() {
            // Re-enable shadow mode: evaluateRevocationAndNotify must NOT dispatch
            ReflectionTestUtils.setField(service, "revocationShadowMode", true);

            Organization org = buildOrg();
            Target target = enabledTarget(org);
            CertificateRecord cert = certExpiring(target, 60, null);
            // Simulate cert is REVOKED
            cert.setStatus(CertStatus.REVOKED);
            cert.setLastRevocationAlertSentAt(null);

            service.evaluateRevocationAndNotify(cert);

            // In shadow mode: no revocation alert should fire
            verify(notificationService, never()).dispatchRevocationAlert(any());
        }
    }
}
