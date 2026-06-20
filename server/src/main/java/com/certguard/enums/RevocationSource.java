package com.certguard.enums;

/**
 * The revocation data source that produced the final RevocationStatus.
 *
 * Stored as text. RFC 0009 §4.2.
 */
public enum RevocationSource {
    /** OCSP response embedded in the TLS handshake (Certificate Status extension). */
    OCSP_STAPLED,
    /** Online OCSP request to the AIA responder URL. */
    OCSP,
    /** Certificate Revocation List fetched from the CDP URL. */
    CRL,
    /** No revocation source was available or applicable. */
    NONE
}
