package com.certguard.enums;

/**
 * Certificate status values persisted in the PostgreSQL cert_status ENUM.
 *
 * <p>Precedence order (RFC 0009 §3.5):
 * {@code REVOKED > EXPIRED > INVALID > EXPIRING > VALID}
 * ({@code UNREACHABLE} and {@code UNKNOWN} are orthogonal — set when no cert is reachable.)
 */
public enum CertStatus {
    VALID,
    EXPIRING,
    EXPIRED,
    UNREACHABLE,
    UNKNOWN,
    /** Certificate has been definitively revoked (confirmed via OCSP or CRL). RFC 0009. */
    REVOKED,
    /** Public target presents an untrusted/invalid chain. RFC 0009 §10.4. */
    INVALID
}
