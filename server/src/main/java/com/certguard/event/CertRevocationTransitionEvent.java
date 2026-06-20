package com.certguard.event;

import com.certguard.enums.CertStatus;
import com.certguard.enums.RevocationSource;
import com.certguard.enums.RevocationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Fired on every revocation-status transition (RFC 0009 §4.4 / BE-10).
 *
 * <p>v1 subscribers: WARN logger + Micrometer counter only.
 * Future: an {@code @TransactionalEventListener(phase = AFTER_COMMIT)} can persist
 * each event to {@code certificate_revocation_events} without changing this class.
 *
 * <p>Fired by:
 * <ul>
 *   <li>{@link com.certguard.service.RevocationRecheckScheduler} (daily recheck)</li>
 *   <li>Scan paths (server and agent) whenever revocation status changes</li>
 * </ul>
 */
public record CertRevocationTransitionEvent(
        UUID certId,
        UUID orgId,
        CertStatus previousCertStatus,
        CertStatus newCertStatus,
        RevocationStatus previousRevocationStatus,
        RevocationStatus newRevocationStatus,
        String reason,
        RevocationSource source,
        Instant observedAt,
        boolean deepCheck
) {}
