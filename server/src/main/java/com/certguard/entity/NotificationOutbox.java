package com.certguard.entity;

import com.certguard.enums.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Durable send-intent for a single notification recipient (R19 fix).
 *
 * <p>Written in the SAME transaction as the caller's dedup stamp
 * ({@code certificate_records.last_alert_sent_at} /
 * {@code last_revocation_alert_sent_at}) — see
 * {@link com.certguard.service.ExpiryEvaluationService}. This makes the
 * send-intent as durable as the stamp: an SMTP outage no longer loses the
 * alert, it just leaves the row {@code PENDING} for
 * {@link com.certguard.service.NotificationOutboxScheduler} to retry.
 *
 * <p>{@code org_id} is denormalized onto this table (house convention for
 * child tables — see {@code CertificateRecord.orgId}) even though it is not
 * currently queried by org; it keeps future org-scoped tooling (quotas,
 * admin views) a single-column filter away.
 *
 * <p>{@code templateVars} holds the fully-resolved, plain-value Thymeleaf
 * variables (host/port/daysLeft/severity/... or the revocation equivalents) —
 * never a managed JPA entity — so the row can be rendered and sent from any
 * thread with no Hibernate session (mirrors the P1-A fix in
 * {@code ExpiryAlertContext}/{@code RevocationAlertContext}).
 */
@Entity
@Table(name = "notification_outbox")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationOutbox extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "to_address", nullable = false)
    private String toAddress;

    @Column(nullable = false)
    private String subject;

    @Column(name = "template_name", nullable = false)
    private String templateName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_vars", nullable = false)
    @Builder.Default
    private Map<String, Object> templateVars = Map.of();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    @Builder.Default
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;
}
