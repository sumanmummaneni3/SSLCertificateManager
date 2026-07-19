package com.certguard.service;

import com.certguard.dto.internal.ExpiryAlertContext;
import com.certguard.dto.internal.RevocationAlertContext;
import com.certguard.entity.NotificationOutbox;
import com.certguard.enums.OutboxStatus;
import com.certguard.repository.NotificationOutboxRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NotificationOutboxService} (R19 fix).
 *
 * <p>Core invariant under test: an SMTP failure at drain time must leave a retryable
 * PENDING row and must NOT consume the alert (no data loss), while a persistent
 * failure eventually terminates in FAILED after the configured attempt cap — never
 * silently disappearing.
 */
@ExtendWith(MockitoExtension.class)
class NotificationOutboxServiceTest {

    @Mock NotificationOutboxRepository outboxRepository;
    @Mock NotificationService notificationService;

    NotificationOutboxService service;

    @BeforeEach
    void setUp() {
        service = new NotificationOutboxService(outboxRepository, notificationService);
        ReflectionTestUtils.setField(service, "maxAttempts", 6);
        ReflectionTestUtils.setField(service, "backoffBaseSeconds", 60);
        ReflectionTestUtils.setField(service, "backoffMaxSeconds", 3600L);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ExpiryAlertContext expiryCtx(Map<String, Object> channels) {
        return new ExpiryAlertContext(
                UUID.randomUUID(), "example.com", 443, 10, "WARNING",
                UUID.randomUUID(), false, channels);
    }

    private RevocationAlertContext revocationCtx(Map<String, Object> channels) {
        return new RevocationAlertContext(
                UUID.randomUUID(), "example.com", 443, UUID.randomUUID(),
                "KEY_COMPROMISE", "OCSP", Instant.now(), false, "CRITICAL", channels);
    }

    private Map<String, Object> emailChannels(String... addresses) {
        return Map.of("email", Map.of("enabled", true, "addresses", List.of(addresses)));
    }

    private NotificationOutbox pendingRow() {
        NotificationOutbox row = NotificationOutbox.builder()
                .orgId(UUID.randomUUID())
                .toAddress("ops@example.com")
                .subject("subj")
                .templateName("expiry-warning")
                .templateVars(Map.of("host", "example.com"))
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(Instant.now())
                .build();
        ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
        return row;
    }

    // ── Enqueue ──────────────────────────────────────────────────────────────

    @Nested
    class Enqueue {

        @Test
        void expiryAlert_oneRowPerAddress() {
            when(notificationService.resolveEmailAddresses(any()))
                    .thenReturn(List.of("a@example.com", "b@example.com"));
            when(notificationService.buildExpirySubject(any())).thenReturn("[CertGuard] WARNING");
            when(notificationService.expiryTemplateName(any())).thenReturn("expiry-warning");
            when(notificationService.buildExpiryTemplateVars(any()))
                    .thenReturn(Map.of("host", "example.com"));

            int count = service.enqueueExpiryAlert(expiryCtx(emailChannels("a@example.com", "b@example.com")));

            assertThat(count).isEqualTo(2);
            ArgumentCaptor<NotificationOutbox> cap = ArgumentCaptor.forClass(NotificationOutbox.class);
            verify(outboxRepository, times(2)).save(cap.capture());
            assertThat(cap.getAllValues()).extracting(NotificationOutbox::getToAddress)
                    .containsExactlyInAnyOrder("a@example.com", "b@example.com");
            assertThat(cap.getAllValues()).allSatisfy(row -> {
                assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
                assertThat(row.getAttempts()).isZero();
                assertThat(row.getTemplateName()).isEqualTo("expiry-warning");
                assertThat(row.getSubject()).isEqualTo("[CertGuard] WARNING");
            });
        }

        @Test
        void expiryAlert_noAddresses_noRowsEnqueued() {
            when(notificationService.resolveEmailAddresses(any())).thenReturn(List.of());

            int count = service.enqueueExpiryAlert(expiryCtx(Map.of()));

            assertThat(count).isZero();
            verifyNoInteractions(outboxRepository);
        }

        @Test
        void revocationAlert_oneRowPerAddress() {
            when(notificationService.resolveEmailAddresses(any())).thenReturn(List.of("sec@example.com"));
            when(notificationService.buildRevocationSubject(any())).thenReturn("[CertGuard] REVOKED");
            when(notificationService.buildRevocationTemplateVars(any()))
                    .thenReturn(Map.of("host", "example.com"));

            int count = service.enqueueRevocationAlert(revocationCtx(emailChannels("sec@example.com")));

            assertThat(count).isEqualTo(1);
            ArgumentCaptor<NotificationOutbox> cap = ArgumentCaptor.forClass(NotificationOutbox.class);
            verify(outboxRepository).save(cap.capture());
            assertThat(cap.getValue().getTemplateName()).isEqualTo("revocation-alert");
            assertThat(cap.getValue().getToAddress()).isEqualTo("sec@example.com");
        }

        @Test
        void revocationAlert_noAddresses_noRowsEnqueued() {
            when(notificationService.resolveEmailAddresses(any())).thenReturn(List.of());

            int count = service.enqueueRevocationAlert(revocationCtx(Map.of()));

            assertThat(count).isZero();
            verifyNoInteractions(outboxRepository);
        }
    }

    // ── Drain: success ───────────────────────────────────────────────────────

    @Nested
    class ProcessOneSuccess {

        @Test
        void sendSucceeds_markedSentWithTimestamp() {
            NotificationOutbox row = pendingRow();
            when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));
            when(notificationService.sendOutboxEmail(anyString(), anyString(), anyString(), any()))
                    .thenReturn(false); // real send — not devMode-suppressed

            boolean result = service.processOne(row.getId());

            assertThat(result).isTrue();
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(row.getSentAt()).isNotNull();
            assertThat(row.getLastError()).isNull();
            verify(notificationService).sendOutboxEmail(
                    row.getToAddress(), row.getSubject(), row.getTemplateName(), row.getTemplateVars());
            verify(outboxRepository).save(row);
        }

        /**
         * Dev mode legitimately means "delivery is a no-op" — the row is still SENT — but
         * it must stay distinguishable from a genuine delivery so a promoted/inspected DB
         * doesn't report clean delivery for mail that never left the box.
         */
        @Test
        void devModeSuppressedSend_markedSentWithMarkerInLastError() {
            NotificationOutbox row = pendingRow();
            when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));
            when(notificationService.sendOutboxEmail(anyString(), anyString(), anyString(), any()))
                    .thenReturn(true); // devMode short-circuit — no SMTP transaction occurred

            boolean result = service.processOne(row.getId());

            assertThat(result).isTrue();
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(row.getSentAt()).isNotNull();
            assertThat(row.getLastError()).isEqualTo(NotificationOutboxService.DEV_MODE_SUPPRESSED_MARKER);
            verify(outboxRepository).save(row);
        }

        @Test
        void rowNotFound_returnsTrue_noSendAttempted() {
            UUID id = UUID.randomUUID();
            when(outboxRepository.findById(id)).thenReturn(Optional.empty());

            boolean result = service.processOne(id);

            assertThat(result).isTrue();
            verifyNoInteractions(notificationService);
        }

        @Test
        void rowAlreadySent_notReprocessed() {
            NotificationOutbox row = pendingRow();
            row.setStatus(OutboxStatus.SENT);
            when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));

            boolean result = service.processOne(row.getId());

            assertThat(result).isTrue();
            verifyNoInteractions(notificationService);
            verify(outboxRepository, never()).save(any());
        }
    }

    // ── Drain: failure / retry / exhaustion — the key R19 invariant ────────────

    @Nested
    class ProcessOneFailure {

        /**
         * Core invariant: SMTP failure must leave a retryable PENDING row and must NOT
         * consume the alert. The row is never deleted or silently dropped.
         */
        @Test
        void smtpFailure_leavesRowPending_incrementsAttempts_recordsError() {
            NotificationOutbox row = pendingRow();
            when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));
            doThrow(new RuntimeException("SMTP relay unreachable"))
                    .when(notificationService).sendOutboxEmail(anyString(), anyString(), anyString(), any());

            boolean result = service.processOne(row.getId());

            assertThat(result).isFalse();
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING); // still retryable — not consumed
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getLastError()).contains("SMTP relay unreachable");
            assertThat(row.getSentAt()).isNull();
            verify(outboxRepository).save(row);
        }

        @Test
        void backoff_isExponential_basedOnAttemptCount() {
            NotificationOutbox row = pendingRow();
            row.setAttempts(2); // about to become attempt 3
            when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));
            doThrow(new RuntimeException("down"))
                    .when(notificationService).sendOutboxEmail(anyString(), anyString(), anyString(), any());

            Instant before = Instant.now();
            service.processOne(row.getId());

            // attempt 3, base=60s → 60 * 2^(3-1) = 240s
            assertThat(row.getNextAttemptAt()).isCloseTo(
                    before.plusSeconds(240), org.assertj.core.api.Assertions.within(5, ChronoUnit.SECONDS));
        }

        @Test
        void backoff_isClampedToMax_andDoesNotOverflow() {
            // R19: max-attempts is high enough to ride out a multi-day outage, so attempt
            // counts reach values where an unclamped 60 * 2^(N-1) would overflow the shift
            // and yield a negative (immediately-due) or absurd next_attempt_at.
            ReflectionTestUtils.setField(service, "maxAttempts", 200);
            NotificationOutbox row = pendingRow();
            row.setAttempts(120);
            when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));
            doThrow(new RuntimeException("relay still down"))
                    .when(notificationService).sendOutboxEmail(anyString(), anyString(), anyString(), any());

            Instant before = Instant.now();
            service.processOne(row.getId());

            assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(row.getNextAttemptAt())
                    .as("clamped to backoffMaxSeconds, never in the past")
                    .isAfter(before)
                    .isCloseTo(before.plusSeconds(3600),
                            org.assertj.core.api.Assertions.within(5, ChronoUnit.SECONDS));
        }

        @Test
        void attemptCapExhausted_markedFailed_notRetried() {
            NotificationOutbox row = pendingRow();
            row.setAttempts(5); // this failure is the 6th (== maxAttempts)
            when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));
            doThrow(new RuntimeException("permanent failure"))
                    .when(notificationService).sendOutboxEmail(anyString(), anyString(), anyString(), any());

            boolean result = service.processOne(row.getId());

            assertThat(result).isFalse();
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(row.getAttempts()).isEqualTo(6);
            verify(outboxRepository).save(row);
        }

        @Test
        void failedRow_remainsQueryable_notDeleted() {
            // FAILED rows must stay in the table for ops visibility — save(), never delete.
            NotificationOutbox row = pendingRow();
            row.setAttempts(5);
            when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));
            doThrow(new RuntimeException("down"))
                    .when(notificationService).sendOutboxEmail(anyString(), anyString(), anyString(), any());

            service.processOne(row.getId());

            verify(outboxRepository, never()).delete(any());
            verify(outboxRepository, never()).deleteById(any());
            verify(outboxRepository).save(row);
        }
    }
}
