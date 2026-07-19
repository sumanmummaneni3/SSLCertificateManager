package com.certguard.repository;

import com.certguard.entity.NotificationOutbox;
import com.certguard.enums.OutboxStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.certguard.DockerAvailableCondition;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Repository-layer tests for {@link NotificationOutboxRepository#aggregateDeliveryStatus} and
 * {@link NotificationOutboxRepository#findRecentErrors}, the queries backing
 * {@code GET /api/v1/organizations/{orgId}/notifications/delivery-status}.
 *
 * <p>Uses a real Postgres container (Testcontainers) — see {@link TargetRepositoryTest} for
 * rationale (Postgres-specific ENUM/JSONB types, @DataJpaTest unavailable in Spring Boot 4.0).
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest
@ActiveProfiles("tctest")
@Transactional
class NotificationOutboxRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_outbox_agg_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);
        registry.add("spring.flyway.enabled",       () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.type.preferred_enum_jdbc_type", () -> "VARCHAR");
        registry.add("server.ssl.enabled",          () -> "false");
        registry.add("spring.rabbitmq.host",        () -> "localhost");
    }

    @Autowired NotificationOutboxRepository outboxRepository;
    @PersistenceContext EntityManager em;

    private NotificationOutbox row(UUID orgId, OutboxStatus status, int attempts,
                                    String lastError, Instant createdAt, Instant nextAttemptAt) {
        NotificationOutbox o = NotificationOutbox.builder()
                .orgId(orgId)
                .toAddress("ops@example.com")
                .subject("subj")
                .templateName("expiry-warning")
                .templateVars(Map.of("host", "example.com"))
                .status(status)
                .attempts(attempts)
                .lastError(lastError)
                .nextAttemptAt(nextAttemptAt != null ? nextAttemptAt : Instant.now())
                .build();
        em.persist(o);
        em.flush();
        // createdAt is @CreationTimestamp-managed; overwrite via native update so ordering is
        // deterministic for oldest-row assertions.
        if (createdAt != null) {
            em.createNativeQuery("UPDATE notification_outbox SET created_at = ?1 WHERE id = ?2")
                    .setParameter(1, createdAt)
                    .setParameter(2, o.getId())
                    .executeUpdate();
        }
        return o;
    }

    @Nested
    class AggregateDeliveryStatus {

        @Test
        void noRows_returnsZeroCountsAndNullTimestamps() {
            UUID orgId = UUID.randomUUID();

            DeliveryStatusAggregate agg = outboxRepository.aggregateDeliveryStatus(orgId);

            assertThat(agg.queuedCount()).isZero();
            assertThat(agg.failedCount()).isZero();
            assertThat(agg.oldestUndeliveredAt()).isNull();
            assertThat(agg.nextAttemptAt()).isNull();
        }

        @Test
        void pendingWithZeroAttempts_notCountedAsQueued() {
            UUID orgId = UUID.randomUUID();
            row(orgId, OutboxStatus.PENDING, 0, null, null, null);
            em.clear();

            DeliveryStatusAggregate agg = outboxRepository.aggregateDeliveryStatus(orgId);

            assertThat(agg.queuedCount()).isZero();
            assertThat(agg.failedCount()).isZero();
            assertThat(agg.oldestUndeliveredAt()).isNull();
        }

        @Test
        void pendingWithAttempts_countedAsQueued() {
            UUID orgId = UUID.randomUUID();
            Instant created = Instant.now().minus(2, ChronoUnit.HOURS);
            Instant next = Instant.now().plusSeconds(300);
            row(orgId, OutboxStatus.PENDING, 3, "SMTP timeout", created, next);
            em.clear();

            DeliveryStatusAggregate agg = outboxRepository.aggregateDeliveryStatus(orgId);

            assertThat(agg.queuedCount()).isEqualTo(1);
            assertThat(agg.failedCount()).isZero();
            assertThat(agg.oldestUndeliveredAt()).isCloseTo(created, within(2, ChronoUnit.SECONDS));
            assertThat(agg.nextAttemptAt()).isCloseTo(next, within(2, ChronoUnit.SECONDS));
        }

        @Test
        void failedRows_countedAsFailed_andContributeToOldest() {
            UUID orgId = UUID.randomUUID();
            Instant created = Instant.now().minus(3, ChronoUnit.DAYS);
            row(orgId, OutboxStatus.FAILED, 200, "attempt cap exhausted", created, null);
            em.clear();

            DeliveryStatusAggregate agg = outboxRepository.aggregateDeliveryStatus(orgId);

            assertThat(agg.queuedCount()).isZero();
            assertThat(agg.failedCount()).isEqualTo(1);
            assertThat(agg.oldestUndeliveredAt()).isCloseTo(created, within(2, ChronoUnit.SECONDS));
            // FAILED rows are terminal — never contribute to nextAttemptAt.
            assertThat(agg.nextAttemptAt()).isNull();
        }

        @Test
        void sentRows_neverCounted() {
            UUID orgId = UUID.randomUUID();
            row(orgId, OutboxStatus.SENT, 1, null, null, null);
            em.clear();

            DeliveryStatusAggregate agg = outboxRepository.aggregateDeliveryStatus(orgId);

            assertThat(agg.queuedCount()).isZero();
            assertThat(agg.failedCount()).isZero();
        }

        @Test
        void oldestUndeliveredAt_isMinimumAcrossQueuedAndFailed() {
            UUID orgId = UUID.randomUUID();
            Instant older = Instant.now().minus(5, ChronoUnit.DAYS);
            Instant newer = Instant.now().minus(1, ChronoUnit.HOURS);
            row(orgId, OutboxStatus.FAILED, 200, "permanent", older, null);
            row(orgId, OutboxStatus.PENDING, 2, "transient", newer, Instant.now().plusSeconds(60));
            em.clear();

            DeliveryStatusAggregate agg = outboxRepository.aggregateDeliveryStatus(orgId);

            assertThat(agg.queuedCount()).isEqualTo(1);
            assertThat(agg.failedCount()).isEqualTo(1);
            assertThat(agg.oldestUndeliveredAt()).isCloseTo(older, within(2, ChronoUnit.SECONDS));
        }

        @Test
        void crossOrgIsolation_otherOrgRowsNeverCounted() {
            UUID orgA = UUID.randomUUID();
            UUID orgB = UUID.randomUUID();
            row(orgA, OutboxStatus.PENDING, 5, "orgA failing", null, null);
            row(orgA, OutboxStatus.FAILED, 200, "orgA failed", null, null);
            row(orgB, OutboxStatus.PENDING, 0, null, null, null); // healthy org B row
            em.clear();

            DeliveryStatusAggregate aggA = outboxRepository.aggregateDeliveryStatus(orgA);
            DeliveryStatusAggregate aggB = outboxRepository.aggregateDeliveryStatus(orgB);

            assertThat(aggA.queuedCount()).isEqualTo(1);
            assertThat(aggA.failedCount()).isEqualTo(1);
            assertThat(aggB.queuedCount()).isZero();
            assertThat(aggB.failedCount()).isZero();
        }
    }

    @Nested
    class PurgeQueries {

        private NotificationOutbox sentRow(UUID orgId, Instant sentAt) {
            NotificationOutbox o = NotificationOutbox.builder()
                    .orgId(orgId)
                    .toAddress("ops@example.com")
                    .subject("subj")
                    .templateName("expiry-warning")
                    .templateVars(Map.of("host", "example.com"))
                    .status(OutboxStatus.SENT)
                    .attempts(1)
                    .nextAttemptAt(Instant.now())
                    .sentAt(sentAt)
                    .build();
            em.persist(o);
            em.flush();
            return o;
        }

        private NotificationOutbox failedRow(UUID orgId, Instant updatedAt) {
            NotificationOutbox o = NotificationOutbox.builder()
                    .orgId(orgId)
                    .toAddress("ops@example.com")
                    .subject("subj")
                    .templateName("expiry-warning")
                    .templateVars(Map.of("host", "example.com"))
                    .status(OutboxStatus.FAILED)
                    .attempts(200)
                    .lastError("attempt cap exhausted")
                    .nextAttemptAt(Instant.now())
                    .build();
            em.persist(o);
            em.flush();
            // updated_at is DB-trigger-maintained (V1's update_updated_at()) — force it via
            // native update, same pattern the class-level row() helper uses for created_at.
            em.createNativeQuery("UPDATE notification_outbox SET updated_at = ?1 WHERE id = ?2")
                    .setParameter(1, updatedAt)
                    .setParameter(2, o.getId())
                    .executeUpdate();
            return o;
        }

        @Test
        void findSentIdsOlderThan_onlyReturnsSentRowsPastCutoff() {
            UUID orgId = UUID.randomUUID();
            Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
            NotificationOutbox old = sentRow(orgId, cutoff.minus(1, ChronoUnit.DAYS));
            sentRow(orgId, cutoff.plus(1, ChronoUnit.DAYS)); // too recent — excluded
            failedRow(orgId, cutoff.minus(1, ChronoUnit.DAYS)); // wrong status — excluded
            em.clear();

            List<UUID> ids = outboxRepository.findSentIdsOlderThan(cutoff, PageRequest.of(0, 500));

            assertThat(ids).containsExactly(old.getId());
        }

        @Test
        void findFailedIdsOlderThan_onlyReturnsFailedRowsPastCutoff() {
            UUID orgId = UUID.randomUUID();
            Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);
            NotificationOutbox old = failedRow(orgId, cutoff.minus(1, ChronoUnit.DAYS));
            failedRow(orgId, cutoff.plus(1, ChronoUnit.DAYS)); // too recent — excluded
            sentRow(orgId, cutoff.minus(1, ChronoUnit.DAYS)); // wrong status — excluded
            em.clear();

            List<UUID> ids = outboxRepository.findFailedIdsOlderThan(cutoff, PageRequest.of(0, 500));

            assertThat(ids).containsExactly(old.getId());
        }

        @Test
        void findSentIdsOlderThan_respectsPageSize() {
            UUID orgId = UUID.randomUUID();
            Instant cutoff = Instant.now();
            for (int i = 0; i < 5; i++) {
                sentRow(orgId, cutoff.minus(1, ChronoUnit.DAYS));
            }
            em.clear();

            List<UUID> ids = outboxRepository.findSentIdsOlderThan(cutoff, PageRequest.of(0, 3));

            assertThat(ids).hasSize(3);
        }

        @Test
        void deleteAllByIdInBatch_removesExactlyTheGivenRows() {
            UUID orgId = UUID.randomUUID();
            NotificationOutbox toDelete = sentRow(orgId, Instant.now().minus(40, ChronoUnit.DAYS));
            NotificationOutbox toKeep = sentRow(orgId, Instant.now());
            em.clear();

            outboxRepository.deleteAllByIdInBatch(List.of(toDelete.getId()));
            em.clear();

            assertThat(outboxRepository.findById(toDelete.getId())).isEmpty();
            assertThat(outboxRepository.findById(toKeep.getId())).isPresent();
        }
    }

    @Nested
    class FindRecentErrors {

        @Test
        void returnsMostRecentlyUpdatedError_amongQueuedOrFailed() throws InterruptedException {
            UUID orgId = UUID.randomUUID();
            NotificationOutbox first = row(orgId, OutboxStatus.PENDING, 1, "first error", null, null);
            // Force a distinguishable updated_at ordering.
            Thread.sleep(5);
            NotificationOutbox second = row(orgId, OutboxStatus.FAILED, 200, "second error", null, null);
            em.clear();

            List<String> recent = outboxRepository.findRecentErrors(orgId, PageRequest.of(0, 1));

            assertThat(recent).hasSize(1);
            assertThat(recent.get(0)).isEqualTo("second error");
        }

        @Test
        void excludesPendingWithZeroAttempts_evenWithLastError() {
            UUID orgId = UUID.randomUUID();
            // attempts=0 rows shouldn't normally carry a lastError, but the query must still
            // exclude them defensively.
            row(orgId, OutboxStatus.PENDING, 0, "should not appear", null, null);
            em.clear();

            List<String> recent = outboxRepository.findRecentErrors(orgId, PageRequest.of(0, 1));

            assertThat(recent).isEmpty();
        }

        @Test
        void crossOrgIsolation_neverReturnsOtherOrgError() {
            UUID orgA = UUID.randomUUID();
            UUID orgB = UUID.randomUUID();
            row(orgB, OutboxStatus.FAILED, 200, "orgB secret error", null, null);
            em.clear();

            List<String> recent = outboxRepository.findRecentErrors(orgA, PageRequest.of(0, 1));

            assertThat(recent).isEmpty();
        }
    }
}
