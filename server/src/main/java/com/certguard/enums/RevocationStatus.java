package com.certguard.enums;

/**
 * Revocation check outcome for a certificate.
 *
 * Stored as a text column (not a Postgres ENUM) to keep migrations simple.
 * RFC 0009 §4.2.
 */
public enum RevocationStatus {
    /** Confirmed not revoked (OCSP GOOD or not listed in CRL). */
    GOOD,
    /** Confirmed revoked (OCSP REVOKED or listed in CRL). Sets CertStatus=REVOKED. */
    REVOKED,
    /** Indeterminate — responder unreachable/stale/unverifiable (soft-fail). */
    UNKNOWN,
    /** No revocation info available in cert (no AIA/CDP); self-signed or private CA. */
    UNCHECKED
}
