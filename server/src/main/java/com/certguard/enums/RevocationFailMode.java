package com.certguard.enums;

/**
 * Per-org revocation failure-handling mode (RFC 0009 §6).
 *
 * Stored as text in {@code notification_settings.revocation_fail_mode}.
 */
public enum RevocationFailMode {
    /**
     * Default. Unreachable/stale/unverifiable responders → UNKNOWN (no CertStatus change).
     * Only definitive REVOKED from a verified response sets CertStatus=REVOKED.
     */
    SOFT,
    /**
     * Elevated-alert mode. Non-definitive outcomes raise an advisory alert so the
     * operator knows revocation assurance is degraded. Never fabricates REVOKED.
     */
    HARD
}
