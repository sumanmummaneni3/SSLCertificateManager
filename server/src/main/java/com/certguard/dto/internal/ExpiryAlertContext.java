package com.certguard.dto.internal;

import java.util.Map;
import java.util.UUID;

/**
 * Immutable snapshot of everything {@link com.certguard.service.NotificationService}
 * needs to dispatch a certificate-expiry alert.
 *
 * <h3>Why this exists (P1-A)</h3>
 * {@code NotificationService.dispatchExpiryAlert} is {@code @Async}: it runs on a
 * thread-pool thread with no Hibernate session. If the caller passes a JPA managed
 * entity ({@code CertificateRecord}) across the transaction boundary and that entity
 * has un-initialised lazy associations ({@code target.organization},
 * {@code target.agent}), Hibernate throws {@code LazyInitializationException} on the
 * async thread, the executor swallows it, and the alert email is silently lost.
 *
 * The fix is to capture all required data <em>before</em> the transaction commits —
 * while the Hibernate session is still open — into this plain-Java value object, and
 * pass the context across the AFTER_COMMIT / async boundary instead of the entity.
 *
 * @param certId         UUID of the {@code certificate_records} row (for the deep-link URL)
 * @param host           target hostname
 * @param port           target port
 * @param daysLeft       days until certificate expiry (negative = already expired)
 * @param severity       "WARNING" or "CRITICAL"
 * @param orgId          organisation UUID (used for log / channel resolution already done)
 * @param agentDiscovered {@code true} when the target has an assigned agent (pre-computed
 *                        pre-commit so no lazy access is needed post-commit)
 * @param channels       resolved notification channels map — the result of
 *                       {@code NotificationService.resolveChannels(target)} called while
 *                       the session was open; may be empty but never null
 */
public record ExpiryAlertContext(
        UUID certId,
        String host,
        int port,
        int daysLeft,
        String severity,
        UUID orgId,
        boolean agentDiscovered,
        Map<String, Object> channels
) {}
