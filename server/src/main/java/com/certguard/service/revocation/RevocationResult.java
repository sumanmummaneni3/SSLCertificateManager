package com.certguard.service.revocation;

import com.certguard.enums.RevocationSource;
import com.certguard.enums.RevocationStatus;

import java.time.Instant;

/**
 * Immutable result of {@link RevocationCheckService#check}.
 *
 * RFC 0009 §5.2: only a signature-verified definitive REVOKED sets
 * {@link RevocationStatus#REVOKED}. All other non-definitive outcomes
 * resolve to {@link RevocationStatus#UNKNOWN} under soft-fail.
 */
public record RevocationResult(
        RevocationStatus status,
        RevocationSource source,
        /** Mapped reason string (e.g. "KEY_COMPROMISE"); null unless REVOKED. */
        String reason,
        /** Raw RFC 5280 CRLReason code 0..10; null unless REVOKED. */
        Integer reasonCode,
        /** Timestamp from the OCSP/CRL response; null unless REVOKED. */
        Instant revokedAt,
        Instant checkedAt,
        /** Non-null only on UNKNOWN — human-readable reason for the indeterminate. */
        String softFailMessage
) {
    // ── Factory methods ────────────────────────────────────────────────────────

    public static RevocationResult good(RevocationSource source) {
        return new RevocationResult(RevocationStatus.GOOD, source,
                null, null, null, Instant.now(), null);
    }

    public static RevocationResult revoked(RevocationSource source, String reason,
                                            Integer reasonCode, Instant revokedAt) {
        return new RevocationResult(RevocationStatus.REVOKED, source,
                reason, reasonCode, revokedAt, Instant.now(), null);
    }

    public static RevocationResult unknown(RevocationSource source, String message) {
        return new RevocationResult(RevocationStatus.UNKNOWN, source,
                null, null, null, Instant.now(), message);
    }

    public static RevocationResult unchecked(RevocationSource source) {
        return new RevocationResult(RevocationStatus.UNCHECKED, source,
                null, null, null, Instant.now(), null);
    }

    /** True when this cert is on a reversible certificateHold (code 6). */
    public boolean isOnHold() {
        return RevocationStatus.REVOKED == status
                && reasonCode != null && reasonCode == 6;
    }
}
