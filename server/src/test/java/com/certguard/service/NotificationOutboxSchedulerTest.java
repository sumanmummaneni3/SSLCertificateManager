package com.certguard.service;

import com.certguard.enums.OutboxStatus;
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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NotificationOutboxScheduler} — the thin drain loop
 * (R19 fix). Verifies batch resilience: one bad row must not abort the batch.
 */
@ExtendWith(MockitoExtension.class)
class NotificationOutboxSchedulerTest {

    @Mock NotificationOutboxService outboxService;

    NotificationOutboxScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new NotificationOutboxScheduler(outboxService);
        ReflectionTestUtils.setField(scheduler, "batchSize", 200);
        ReflectionTestUtils.setField(scheduler, "sentRetentionDays", 30);
        ReflectionTestUtils.setField(scheduler, "failedRetentionDays", 90);
        ReflectionTestUtils.setField(scheduler, "purgeBatchSize", 500);
    }

    @Nested
    class HappyPath {

        @Test
        void noDueRows_noProcessing() {
            when(outboxService.findDueIds(200)).thenReturn(List.of());

            scheduler.drain();

            verify(outboxService, never()).processOne(any());
        }

        @Test
        void dueRows_eachProcessedOnce() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            when(outboxService.findDueIds(200)).thenReturn(List.of(id1, id2));
            when(outboxService.processOne(any())).thenReturn(true);

            scheduler.drain();

            verify(outboxService).processOne(id1);
            verify(outboxService).processOne(id2);
        }

        @Test
        void batchSizeConfig_passedToFindDueIds() {
            ReflectionTestUtils.setField(scheduler, "batchSize", 50);
            when(outboxService.findDueIds(50)).thenReturn(List.of());

            scheduler.drain();

            verify(outboxService).findDueIds(50);
        }
    }

    @Nested
    class BatchResilience {

        @Test
        void oneRowThrowsUnexpectedException_restOfBatchStillProcessed() {
            UUID bad = UUID.randomUUID();
            UUID good1 = UUID.randomUUID();
            UUID good2 = UUID.randomUUID();
            when(outboxService.findDueIds(200)).thenReturn(List.of(bad, good1, good2));
            doThrow(new RuntimeException("unexpected bug")).when(outboxService).processOne(bad);
            when(outboxService.processOne(good1)).thenReturn(true);
            when(outboxService.processOne(good2)).thenReturn(true);

            // Must not throw — the whole drain tick completes.
            scheduler.drain();

            verify(outboxService).processOne(bad);
            verify(outboxService).processOne(good1);
            verify(outboxService).processOne(good2);
        }

        @Test
        void mixOfSentAndRetried_allRowsAttempted() {
            UUID sent = UUID.randomUUID();
            UUID retried = UUID.randomUUID();
            when(outboxService.findDueIds(200)).thenReturn(List.of(sent, retried));
            when(outboxService.processOne(sent)).thenReturn(true);
            when(outboxService.processOne(retried)).thenReturn(false); // recorded as retry/failed

            scheduler.drain();

            verify(outboxService).processOne(sent);
            verify(outboxService).processOne(retried);
        }
    }

    @Nested
    class PurgeExpiredRows {

        @Test
        void noPurgeableRows_singleEmptyBatchPerStatus() {
            when(outboxService.findPurgeableIds(eq(OutboxStatus.SENT), any(), eq(500)))
                    .thenReturn(List.of());
            when(outboxService.findPurgeableIds(eq(OutboxStatus.FAILED), any(), eq(500)))
                    .thenReturn(List.of());
            when(outboxService.purgeBatch(List.of())).thenReturn(0);

            scheduler.purgeExpiredRows();

            verify(outboxService, times(1)).findPurgeableIds(eq(OutboxStatus.SENT), any(), eq(500));
            verify(outboxService, times(1)).findPurgeableIds(eq(OutboxStatus.FAILED), any(), eq(500));
        }

        @Test
        void bothStatusesPurged_independently() {
            UUID sentId = UUID.randomUUID();
            UUID failedId = UUID.randomUUID();
            when(outboxService.findPurgeableIds(eq(OutboxStatus.SENT), any(), eq(500)))
                    .thenReturn(List.of(sentId));
            when(outboxService.findPurgeableIds(eq(OutboxStatus.FAILED), any(), eq(500)))
                    .thenReturn(List.of(failedId));
            when(outboxService.purgeBatch(List.of(sentId))).thenReturn(1);
            when(outboxService.purgeBatch(List.of(failedId))).thenReturn(1);

            scheduler.purgeExpiredRows();

            verify(outboxService).purgeBatch(List.of(sentId));
            verify(outboxService).purgeBatch(List.of(failedId));
        }

        @Test
        void loopsUntilBatchComesBackShort_notASingleUnboundedDelete() {
            ReflectionTestUtils.setField(scheduler, "purgeBatchSize", 2);
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            // First call: a full batch (size == purgeBatchSize) — must trigger a second call.
            // Second call: a short batch (size < purgeBatchSize) — must terminate the loop.
            when(outboxService.findPurgeableIds(eq(OutboxStatus.SENT), any(), eq(2)))
                    .thenReturn(List.of(a, b))
                    .thenReturn(List.of(c));
            when(outboxService.findPurgeableIds(eq(OutboxStatus.FAILED), any(), eq(2)))
                    .thenReturn(List.of());
            when(outboxService.purgeBatch(List.of(a, b))).thenReturn(2);
            when(outboxService.purgeBatch(List.of(c))).thenReturn(1);

            scheduler.purgeExpiredRows();

            verify(outboxService, times(2)).findPurgeableIds(eq(OutboxStatus.SENT), any(), eq(2));
            verify(outboxService).purgeBatch(List.of(a, b));
            verify(outboxService).purgeBatch(List.of(c));
        }

        @Test
        void cutoffs_derivedFromConfiguredRetentionDays() {
            ReflectionTestUtils.setField(scheduler, "sentRetentionDays", 30);
            ReflectionTestUtils.setField(scheduler, "failedRetentionDays", 90);
            when(outboxService.findPurgeableIds(any(), any(), anyInt())).thenReturn(List.of());

            scheduler.purgeExpiredRows();

            ArgumentCaptor<Instant> sentCutoff = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> failedCutoff = ArgumentCaptor.forClass(Instant.class);
            verify(outboxService).findPurgeableIds(eq(OutboxStatus.SENT), sentCutoff.capture(), anyInt());
            verify(outboxService).findPurgeableIds(eq(OutboxStatus.FAILED), failedCutoff.capture(), anyInt());

            assertThat(sentCutoff.getValue())
                    .isCloseTo(Instant.now().minus(30, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
            assertThat(failedCutoff.getValue())
                    .isCloseTo(Instant.now().minus(90, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
        }
    }
}
