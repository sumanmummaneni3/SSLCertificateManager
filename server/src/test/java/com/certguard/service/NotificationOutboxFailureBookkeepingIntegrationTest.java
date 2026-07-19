package com.certguard.service;

import com.certguard.DockerAvailableCondition;
import com.certguard.entity.NotificationOutbox;
import com.certguard.enums.OutboxStatus;
import com.certguard.repository.NotificationOutboxRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the UnexpectedRollbackException bug fixed in
 * {@link NotificationService#sendOutboxEmail}: NotificationService is class-level
 * {@code @Transactional(readOnly = true)}, so without
 * {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)} on that method, a
 * failed send joins {@link NotificationOutboxService#processOne}'s transaction, marks
 * it rollback-only, and the attempts/last_error/next_attempt_at bookkeeping written in
 * the {@code catch} block is silently discarded — the row sticks at PENDING/attempts=0
 * forever, retried every drain tick with no backoff and never reaching FAILED.
 *
 * <p>Mock-based tests (see {@code NotificationOutboxServiceTest}) mock
 * {@link NotificationService} itself, so they never exercise the real transaction
 * proxy chain and structurally cannot catch this class of bug — it lives in the
 * transaction boundary between two real Spring beans, not in either class's logic.
 * This test therefore wires the real {@link NotificationOutboxService} and the real
 * {@link NotificationService} against a Testcontainers Postgres instance, replacing
 * only the {@link JavaMailSender} at the edge so the send genuinely fails.
 *
 * <p>{@code app.dev-mode=false} is mandatory here: the devMode short-circuit at
 * {@code NotificationService.sendOutboxEmail} returns before ever touching
 * {@code mailSender}, which would make this test vacuous (see {@code MailConfig},
 * which requires dummy {@code spring.mail.username}/{@code password} once devMode is
 * off).
 *
 * <p>Assertions read the row back via {@link NotificationOutboxRepository#findById}
 * from a fresh call after {@code processOne} returns — not from a still-open
 * persistence context — so a regression that reintroduces the rollback bug would
 * actually turn this test red (verified manually: temporarily removing
 * {@code Propagation.NOT_SUPPORTED} from {@code sendOutboxEmail} makes
 * {@code sendFailure_persistsRetryBookkeeping_evenThoughItJoinsProcessOnesTransactionBoundary}
 * fail with attempts=0/last_error=null, confirming the guard).
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("tctest")
class NotificationOutboxFailureBookkeepingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_outbox_bookkeeping_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",             postgres::getJdbcUrl);
        registry.add("spring.datasource.username",         postgres::getUsername);
        registry.add("spring.datasource.password",         postgres::getPassword);
        registry.add("spring.flyway.enabled",              () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto",      () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.type.preferred_enum_jdbc_type", () -> "VARCHAR");
        // Real-send path must be exercised — NOT dev-mode — otherwise the devMode
        // short-circuit in NotificationService.sendOutboxEmail never reaches mailSender
        // and this test would pass regardless of whether the NOT_SUPPORTED guard exists.
        registry.add("app.dev-mode",                       () -> "false");
        // MailConfig fails fast at startup when devMode=false and these are blank.
        registry.add("spring.mail.username",                () -> "test-user");
        registry.add("spring.mail.password",                () -> "test-password");
        registry.add("server.ssl.enabled",                  () -> "false");
        registry.add("spring.rabbitmq.host",                () -> "localhost");
        registry.add("spring.rabbitmq.port",                () -> "5672");
        // Small cap so the exhaustion test doesn't need 200 attempts (app default —
        // see application.yml — is sized for real multi-day outages, not tests).
        registry.add("app.outbox.max-attempts",              () -> "3");
    }

    @Autowired NotificationOutboxService outboxService;
    @Autowired NotificationOutboxRepository outboxRepository;

    @MockitoBean JavaMailSender mailSender;

    UUID orgId;

    @BeforeEach
    void setup() {
        orgId = UUID.randomUUID();
        // Real MimeMessage instance — NotificationService.sendOutboxEmail builds and
        // populates this before handing it to mailSender.send(), so it must be a usable
        // object, not Mockito's default null.
        when(mailSender.createMimeMessage())
                .thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private NotificationOutbox pendingRow(int startingAttempts) {
        return outboxRepository.save(NotificationOutbox.builder()
                .orgId(orgId)
                .toAddress("ops@example.com")
                .subject("Certificate expiring soon")
                .templateName("expiry-warning")
                .templateVars(Map.of(
                        "host", "example.com",
                        "port", 443,
                        "daysLeft", 10,
                        "severity", "WARNING"))
                .status(OutboxStatus.PENDING)
                .attempts(startingAttempts)
                // Deliberately in the future: NotificationOutboxScheduler.drain() is a live
                // @Scheduled bean in this context (fires ~immediately at startup) and would
                // otherwise race this row against our direct processOne(id) call below —
                // findDueIds() filters on nextAttemptAt, so this keeps the scheduler out of
                // the row entirely. processOne(id) itself does not check nextAttemptAt.
                .nextAttemptAt(Instant.now().plusSeconds(3600))
                .build());
    }

    @Test
    void sendFailure_persistsRetryBookkeeping_evenThoughItJoinsProcessOnesTransactionBoundary() {
        NotificationOutbox row = pendingRow(0);
        // The scheduler fence (see pendingRow) — capture it before processOne runs so we
        // can prove recordFailure's ~60s backoff genuinely overwrote it, rather than just
        // asserting isAfter(now), which the fence value alone would also satisfy and
        // would therefore pass identically whether the backoff write landed or was rolled
        // back by a reintroduced UnexpectedRollbackException.
        Instant fencedNextAttemptAt = row.getNextAttemptAt();
        doThrow(new MailSendException("SMTP relay unreachable"))
                .when(mailSender).send(any(MimeMessage.class));

        boolean result = outboxService.processOne(row.getId());

        assertThat(result).isFalse();

        // Read back from the DB via a fresh repository call — not the still-open
        // persistence context from processOne — so a reintroduced rollback bug (writes
        // silently discarded by UnexpectedRollbackException) actually fails this assertion.
        NotificationOutbox persisted = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(persisted.getAttempts()).isEqualTo(1);
        assertThat(persisted.getLastError()).isNotNull().contains("SMTP relay unreachable");
        assertThat(persisted.getNextAttemptAt())
                .isAfter(Instant.now())            // still in the future — retryable, not overdue
                .isBefore(fencedNextAttemptAt);     // but rescheduled by recordFailure's backoff,
                                                     // not left at the +3600s scheduler fence
        assertThat(persisted.getSentAt()).isNull();
    }

    @Test
    void sendFailure_exhaustingMaxAttempts_landsInFailedWithAttemptsAtCap() {
        // app.outbox.max-attempts is overridden to 3 for this test class (see
        // registerProps) — the real default (application.yml) is 200, sized to ride
        // out multi-day relay outages, not something a test should wait through.
        // Start one below the cap so this single failed attempt pushes it to exactly
        // the cap and terminal FAILED.
        NotificationOutbox row = pendingRow(2);
        doThrow(new MailSendException("permanent failure"))
                .when(mailSender).send(any(MimeMessage.class));

        boolean result = outboxService.processOne(row.getId());

        assertThat(result).isFalse();

        NotificationOutbox persisted = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(persisted.getAttempts()).isEqualTo(3);
        assertThat(persisted.getLastError()).isNotNull().contains("permanent failure");
    }
}
