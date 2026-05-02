package com.certguard.service;

import com.certguard.entity.CertificateRecord;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.repository.CertificateRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CertificateExpiryServiceTest {

    @Mock CertificateRecordRepository certRepository;
    @Mock NotificationService notificationService;

    CertificateExpiryScheduler scheduler;

    UUID orgId;
    Organization org;

    @BeforeEach
    void setUp() {
        scheduler = new CertificateExpiryScheduler(certRepository, notificationService);
        ReflectionTestUtils.setField(scheduler, "warningDays", 30);
        ReflectionTestUtils.setField(scheduler, "criticalDays", 7);
        ReflectionTestUtils.setField(scheduler, "dedupHours", 23);

        orgId = UUID.randomUUID();
        org = Organization.builder().name("Org").build();
        ReflectionTestUtils.setField(org, "id", orgId);
    }

    private Target enabledTarget() {
        return Target.builder().organization(org).host("example.com").port(443).enabled(true).build();
    }

    private CertificateRecord certExpiring(Target target, int daysFromNow) {
        return certExpiring(target, daysFromNow, null);
    }

    private CertificateRecord certExpiring(Target target, int daysFromNow, Instant lastAlertSentAt) {
        Instant expiry = Instant.now().plus(daysFromNow, ChronoUnit.DAYS);
        CertificateRecord cert = CertificateRecord.builder()
                .target(target)
                .orgId(orgId)
                .commonName("example.com")
                .issuer("Test CA")
                .serialNumber("123")
                .expiryDate(expiry)
                .notBefore(Instant.now().minus(30, ChronoUnit.DAYS))
                .build();
        ReflectionTestUtils.setField(cert, "id", UUID.randomUUID());
        cert.setLastAlertSentAt(lastAlertSentAt);
        return cert;
    }

    @Nested
    class Thresholds {

        @Test
        void warningThreshold_30dayBoundary_dispatchesWarning() {
            Target target = enabledTarget();
            CertificateRecord cert = certExpiring(target, 30);

            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of(cert));

            scheduler.checkExpiringCertificates();

            ArgumentCaptor<String> severityCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).dispatchExpiryAlert(eq(target), anyInt(), severityCaptor.capture());
            assertThat(severityCaptor.getValue()).isEqualTo("WARNING");
        }

        @Test
        void criticalThreshold_7dayBoundary_dispatchesCritical() {
            Target target = enabledTarget();
            CertificateRecord cert = certExpiring(target, 7);

            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of(cert));

            scheduler.checkExpiringCertificates();

            ArgumentCaptor<String> severityCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).dispatchExpiryAlert(eq(target), anyInt(), severityCaptor.capture());
            assertThat(severityCaptor.getValue()).isEqualTo("CRITICAL");
        }

        @Test
        void alreadyExpired_negativeDaysLeft_dispatchesCritical() {
            Target target = enabledTarget();
            CertificateRecord cert = certExpiring(target, -5);

            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of(cert));

            scheduler.checkExpiringCertificates();

            ArgumentCaptor<Integer> daysCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(notificationService).dispatchExpiryAlert(eq(target), daysCaptor.capture(), eq("CRITICAL"));
            assertThat(daysCaptor.getValue()).isNegative();
        }
    }

    @Nested
    class SkipCriteria {

        @Test
        void disabledTarget_skipped() {
            Target disabled = Target.builder().organization(org).host("x.com").port(443).enabled(false).build();
            CertificateRecord cert = certExpiring(disabled, 5);

            // Disabled targets are filtered out at the JPQL query level; simulate empty result
            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of());

            scheduler.checkExpiringCertificates();

            verifyNoInteractions(notificationService);
        }

        @Test
        void nullTarget_skipped() {
            CertificateRecord cert = CertificateRecord.builder()
                    .target(null)
                    .orgId(orgId)
                    .commonName("x.com")
                    .issuer("CA")
                    .serialNumber("1")
                    .expiryDate(Instant.now().plus(3, ChronoUnit.DAYS))
                    .notBefore(Instant.now().minus(10, ChronoUnit.DAYS))
                    .build();
            ReflectionTestUtils.setField(cert, "id", UUID.randomUUID());

            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of(cert));

            scheduler.checkExpiringCertificates();

            verifyNoInteractions(notificationService);
        }
    }

    @Nested
    class Deduplication {

        @Test
        void recentlyAlerted_withinDedupWindow_isSkipped() {
            Target target = enabledTarget();
            // last alerted 1 hour ago — within the 23h dedup window
            Instant recentAlert = Instant.now().minus(1, ChronoUnit.HOURS);
            CertificateRecord cert = certExpiring(target, 5, recentAlert);

            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of(cert));

            scheduler.checkExpiringCertificates();

            verifyNoInteractions(notificationService);
            verify(certRepository, never()).stampAlertSentAt(any(), any());
        }

        @Test
        void alertedOutsideDedupWindow_isDispatched() {
            Target target = enabledTarget();
            // last alerted 25 hours ago — outside the 23h dedup window
            Instant oldAlert = Instant.now().minus(25, ChronoUnit.HOURS);
            CertificateRecord cert = certExpiring(target, 5, oldAlert);

            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of(cert));

            scheduler.checkExpiringCertificates();

            verify(notificationService).dispatchExpiryAlert(eq(target), anyInt(), anyString());
            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
        }

        @Test
        void neverAlerted_nullLastAlert_isDispatched() {
            Target target = enabledTarget();
            CertificateRecord cert = certExpiring(target, 3, null);

            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of(cert));

            scheduler.checkExpiringCertificates();

            verify(notificationService).dispatchExpiryAlert(eq(target), anyInt(), anyString());
            verify(certRepository).stampAlertSentAt(eq(cert.getId()), any(Instant.class));
        }

        @Test
        void alreadyExpiredCert_recentlyAlerted_isSkipped() {
            Target target = enabledTarget();
            Instant recentAlert = Instant.now().minus(2, ChronoUnit.HOURS);
            CertificateRecord cert = certExpiring(target, -10, recentAlert);

            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of(cert));

            scheduler.checkExpiringCertificates();

            verifyNoInteractions(notificationService);
        }

        @Test
        void stampAlertSentAt_calledWithNowTimestamp() {
            Target target = enabledTarget();
            CertificateRecord cert = certExpiring(target, 20, null);
            UUID certId = cert.getId();

            when(certRepository.findExpiringWithTargets(any(), any()))
                    .thenReturn(List.of(cert));

            Instant before = Instant.now().minusSeconds(1);
            scheduler.checkExpiringCertificates();
            Instant after = Instant.now().plusSeconds(1);

            ArgumentCaptor<Instant> tsCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(certRepository).stampAlertSentAt(eq(certId), tsCaptor.capture());
            Instant stamped = tsCaptor.getValue();
            assertThat(stamped).isAfter(before).isBefore(after);
        }
    }
}
