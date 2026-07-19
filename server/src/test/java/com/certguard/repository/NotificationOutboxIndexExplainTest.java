package com.certguard.repository;

import com.certguard.DockerAvailableCondition;
import com.certguard.entity.NotificationOutbox;
import com.certguard.entity.Organization;
import com.certguard.enums.OutboxStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the V44 partial index {@code idx_notification_outbox_org_not_sent} is actually
 * usable by {@link NotificationOutboxRepository#aggregateDeliveryStatus} — not just present.
 *
 * <p><b>Why this is a separate test class, not a method in {@code NotificationOutboxRepositoryTest}:</b>
 * every sibling Testcontainers test in this codebase sets {@code spring.jpa.hibernate.ddl-auto
 * =create-drop}. Flyway still runs first under that config (its log shows every migration
 * applying, V44 included), but Hibernate's {@code create-drop} then DROPS and recreates the
 * schema purely from JPA entity mappings — silently discarding every hand-written SQL
 * construct: indexes, non-JPA-mapped CHECK constraints, triggers. A partial index declared
 * only in a {@code V*__*.sql} file therefore never exists in any of those tests' databases,
 * confirmed empirically (queried {@code pg_indexes} against a create-drop-configured context:
 * only the entity-inferred primary key index was present). That is a real, pre-existing gap in
 * this test suite's coverage of hand-written SQL — flagged separately, not fixed here, since
 * it's a suite-wide pattern change, not part of this migration.
 *
 * <p>This class instead inherits the un-overridden {@code application.yml}/
 * {@code application-tctest.yml} defaults: Flyway enabled, {@code ddl-auto=none} (production's
 * actual setting — see RFC 0014's D9 finding: {@code application.yml:24} is {@code none}, not
 * the CLAUDE.md-documented {@code validate}; either leaves a Flyway-applied schema untouched,
 * which is all this test needs). The database this test runs against is therefore the real,
 * fully-migrated V1–V44 schema — the only one where V44's index can be observed at all.
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest
@ActiveProfiles("tctest")
class NotificationOutboxIndexExplainTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_outbox_explain_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);
        registry.add("server.ssl.enabled",          () -> "false");
        registry.add("spring.rabbitmq.host",        () -> "localhost");
        // Deliberately NOT setting spring.flyway.enabled or spring.jpa.hibernate.ddl-auto —
        // see class javadoc for why inheriting the real defaults is the whole point here.
    }

    @Autowired NotificationOutboxRepository outboxRepository;
    @PersistenceContext EntityManager em;

    /**
     * Seeds 50 real organizations and 3000 outbox rows spread across them (org_id has a real
     * FK to organizations under this test's Flyway-applied schema — see class javadoc — unlike
     * the Hibernate-create-drop tests where org_id is an unconstrained UUID column). Enough
     * rows that the planner's cost model actually prefers an index scan over a sequential one:
     * a handful of rows fitting in a single heap page always loses to a seq scan regardless of
     * what indexes exist, which would make an index-usage assertion pass for the wrong reason.
     *
     * @return the org_id used as the "target" org for the EXPLAIN queries
     */
    private UUID seedOrgsAndOutboxRows() {
        List<UUID> orgIds = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Organization org = Organization.builder()
                    .name("Explain Test Org " + i)
                    .slug("explain-test-org-" + UUID.randomUUID())
                    .build();
            em.persist(org);
            orgIds.add(org.getId());
        }
        em.flush();
        UUID targetOrg = orgIds.get(0);

        for (int i = 0; i < 3000; i++) {
            boolean queued = i % 10 == 0;
            NotificationOutbox o = NotificationOutbox.builder()
                    .orgId(orgIds.get(i % orgIds.size()))
                    .toAddress("ops@example.com")
                    .subject("subj")
                    .templateName("expiry-warning")
                    .templateVars(Map.of("host", "example.com"))
                    .status(queued ? OutboxStatus.PENDING : OutboxStatus.SENT)
                    .attempts(queued ? 1 : 0)
                    .lastError(queued ? "SMTP timeout" : null)
                    .nextAttemptAt(Instant.now())
                    .sentAt(queued ? null : Instant.now())
                    .build();
            em.persist(o);
        }
        em.flush();
        em.clear();
        // Fresh Testcontainers database has no table statistics — without ANALYZE the planner
        // has nothing to estimate selectivity from and its choice here wouldn't reflect a real
        // (auto-vacuum-analyzed) production database.
        em.createNativeQuery("ANALYZE notification_outbox").executeUpdate();
        return targetOrg;
    }

    private List<String> explain(String sql) {
        @SuppressWarnings("unchecked")
        List<String> plan = em.createNativeQuery(sql).getResultList();
        return plan;
    }

    @Test
    @Transactional
    void aggregateDeliveryStatusPredicate_plannerUsesPartialIndex_notSeqScan() {
        UUID targetOrg = seedOrgsAndOutboxRows();

        // Mirrors aggregateDeliveryStatus's actual WHERE clause after the V44 companion
        // change: org_id = :orgId AND status <> SENT. UUID inlined as a literal (not a bind
        // parameter) — this is a fixed test-generated value, not user input, and keeps the
        // EXPLAIN query free of native-query UUID parameter-binding concerns.
        String planText = String.join("\n", explain(
                "EXPLAIN SELECT count(*) FROM notification_outbox o "
                        + "WHERE o.org_id = '" + targetOrg + "' AND o.status <> 'SENT'"));
        System.out.println("EXPLAIN plan for aggregateDeliveryStatus predicate (org_id + status<>SENT):\n" + planText);

        assertThat(planText)
                .as("planner should use idx_notification_outbox_org_not_sent (V44) for the "
                        + "org_id + status<>SENT predicate, not a sequential scan — a query "
                        + "polled by the UI banner every few minutes per active session should "
                        + "not scan the whole table as SENT rows accumulate")
                .contains("idx_notification_outbox_org_not_sent")
                .doesNotContain("Seq Scan on notification_outbox");
    }

    /**
     * Architect's request (task #8 review): confirm {@code findRecentErrors} — which already
     * restricted to PENDING-with-attempts-or-FAILED before this migration — can still use the
     * V44 partial index unmodified, given it also filters {@code last_error IS NOT NULL} and
     * orders by {@code updated_at}.
     */
    @Test
    @Transactional
    void findRecentErrorsPredicate_plannerUsesPartialIndex_notSeqScan() {
        UUID targetOrg = seedOrgsAndOutboxRows();

        // Mirrors findRecentErrors's actual WHERE/ORDER BY.
        String planText = String.join("\n", explain(
                "EXPLAIN SELECT last_error FROM notification_outbox o "
                        + "WHERE o.org_id = '" + targetOrg + "' AND o.last_error IS NOT NULL "
                        + "AND ((o.status = 'PENDING' AND o.attempts > 0) OR o.status = 'FAILED') "
                        + "ORDER BY o.updated_at DESC LIMIT 1"));
        System.out.println("EXPLAIN plan for findRecentErrors predicate:\n" + planText);

        assertThat(planText)
                .as("findRecentErrors already restricts to the queued-or-failed subset (a "
                        + "subset of status<>SENT), so it should also use "
                        + "idx_notification_outbox_org_not_sent rather than a sequential scan")
                .contains("idx_notification_outbox_org_not_sent")
                .doesNotContain("Seq Scan on notification_outbox");
    }
}
