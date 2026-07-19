package com.certguard.service;

import com.certguard.dto.response.NotificationDeliveryStatusResponse;
import com.certguard.repository.DeliveryStatusAggregate;
import com.certguard.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationDeliveryStatusService} — the service backing
 * {@code GET /api/v1/organizations/{orgId}/notifications/delivery-status}.
 *
 * <p>Repository aggregation itself (SQL correctness, cross-org isolation) is covered by
 * {@link com.certguard.repository.NotificationOutboxRepositoryTest}; this suite covers the
 * service's degraded/lastError decision logic against a mocked repository.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeliveryStatusServiceTest {

    @Mock NotificationOutboxRepository outboxRepository;

    NotificationDeliveryStatusService service;

    UUID orgId = UUID.randomUUID();

    @Nested
    class Degraded {

        @Test
        void queuedOnly_isDegraded() {
            service = new NotificationDeliveryStatusService(outboxRepository);
            when(outboxRepository.aggregateDeliveryStatus(orgId))
                    .thenReturn(new DeliveryStatusAggregate(2, 0, Instant.now(), Instant.now().plusSeconds(60)));

            NotificationDeliveryStatusResponse resp = service.getDeliveryStatus(orgId, false);

            assertThat(resp.isDegraded()).isTrue();
            assertThat(resp.getQueuedCount()).isEqualTo(2);
            assertThat(resp.getFailedCount()).isZero();
        }

        @Test
        void failedOnly_isDegraded() {
            service = new NotificationDeliveryStatusService(outboxRepository);
            when(outboxRepository.aggregateDeliveryStatus(orgId))
                    .thenReturn(new DeliveryStatusAggregate(0, 3, Instant.now(), null));

            NotificationDeliveryStatusResponse resp = service.getDeliveryStatus(orgId, false);

            assertThat(resp.isDegraded()).isTrue();
            assertThat(resp.getFailedCount()).isEqualTo(3);
        }

        @Test
        void queuedAndFailedBothZero_notDegraded() {
            service = new NotificationDeliveryStatusService(outboxRepository);
            when(outboxRepository.aggregateDeliveryStatus(orgId))
                    .thenReturn(new DeliveryStatusAggregate(0, 0, null, null));

            NotificationDeliveryStatusResponse resp = service.getDeliveryStatus(orgId, false);

            assertThat(resp.isDegraded()).isFalse();
            assertThat(resp.getQueuedCount()).isZero();
            assertThat(resp.getFailedCount()).isZero();
            assertThat(resp.getOldestQueuedAt()).isNull();
            assertThat(resp.getNextAttemptAt()).isNull();
        }

        @Test
        void healthyToDegraded_transitionReflectsLatestAggregate() {
            service = new NotificationDeliveryStatusService(outboxRepository);
            when(outboxRepository.aggregateDeliveryStatus(orgId))
                    .thenReturn(new DeliveryStatusAggregate(0, 0, null, null))
                    .thenReturn(new DeliveryStatusAggregate(1, 0, Instant.now(), Instant.now().plusSeconds(60)));

            assertThat(service.getDeliveryStatus(orgId, false).isDegraded()).isFalse();
            assertThat(service.getDeliveryStatus(orgId, false).isDegraded()).isTrue();
        }
    }

    @Nested
    class LastErrorRedaction {

        @Test
        void nonAdmin_lastErrorAlwaysNull_evenWhenDegraded() {
            service = new NotificationDeliveryStatusService(outboxRepository);
            when(outboxRepository.aggregateDeliveryStatus(orgId))
                    .thenReturn(new DeliveryStatusAggregate(1, 0, Instant.now(), Instant.now()));

            NotificationDeliveryStatusResponse resp = service.getDeliveryStatus(orgId, /* includeLastError= */ false);

            assertThat(resp.getLastError()).isNull();
            verify(outboxRepository, never()).findRecentErrors(any(), any());
        }

        @Test
        void admin_lastErrorPopulated_whenDegraded() {
            service = new NotificationDeliveryStatusService(outboxRepository);
            when(outboxRepository.aggregateDeliveryStatus(orgId))
                    .thenReturn(new DeliveryStatusAggregate(1, 0, Instant.now(), Instant.now()));
            when(outboxRepository.findRecentErrors(eq(orgId), eq(PageRequest.of(0, 1))))
                    .thenReturn(List.of("SMTP relay unreachable"));

            NotificationDeliveryStatusResponse resp = service.getDeliveryStatus(orgId, /* includeLastError= */ true);

            assertThat(resp.getLastError()).isEqualTo("SMTP relay unreachable");
        }

        @Test
        void admin_notDegraded_lastErrorNotQueried() {
            service = new NotificationDeliveryStatusService(outboxRepository);
            when(outboxRepository.aggregateDeliveryStatus(orgId))
                    .thenReturn(new DeliveryStatusAggregate(0, 0, null, null));

            NotificationDeliveryStatusResponse resp = service.getDeliveryStatus(orgId, /* includeLastError= */ true);

            assertThat(resp.getLastError()).isNull();
            verify(outboxRepository, never()).findRecentErrors(any(), any());
        }

        @Test
        void admin_degradedButNoErrorRecorded_lastErrorNull() {
            service = new NotificationDeliveryStatusService(outboxRepository);
            when(outboxRepository.aggregateDeliveryStatus(orgId))
                    .thenReturn(new DeliveryStatusAggregate(1, 0, Instant.now(), Instant.now()));
            when(outboxRepository.findRecentErrors(eq(orgId), eq(PageRequest.of(0, 1))))
                    .thenReturn(List.of());

            NotificationDeliveryStatusResponse resp = service.getDeliveryStatus(orgId, /* includeLastError= */ true);

            assertThat(resp.getLastError()).isNull();
        }
    }

    @Nested
    class ResponseDoesNotLeakRecipients {

        @Test
        void responseHasNoRecipientField() {
            // Structural guard: NotificationDeliveryStatusResponse must never carry
            // toAddress/recipient data, for any role.
            for (var field : NotificationDeliveryStatusResponse.class.getDeclaredFields()) {
                String name = field.getName().toLowerCase();
                assertThat(name).doesNotContain("email").doesNotContain("address").doesNotContain("recipient");
            }
        }
    }
}
