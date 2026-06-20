package com.certguard.dto.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable snapshot of everything needed to dispatch a revocation alert
 * (RFC 0009 §3.6 / BE-9).
 *
 * <p>Parallel to {@link ExpiryAlertContext}: all data is resolved pre-commit while
 * the Hibernate session is still open, preventing LazyInitializationException in
 * the AFTER_COMMIT / async dispatch path.
 *
 * @param certId               UUID of the certificate_records row
 * @param host                 target hostname
 * @param port                 target port
 * @param orgId                organisation UUID
 * @param revocationReason     human-readable reason (e.g. "KEY_COMPROMISE")
 * @param revocationSource     source that produced the REVOKED result (e.g. "OCSP")
 * @param revokedAt            timestamp from the CA response; may be null
 * @param onHold               true iff reason code == 6 (certificateHold / reversible)
 * @param severity             "CRITICAL" for KEY/CA_COMPROMISE, "HIGH" otherwise
 * @param channels             resolved notification channels (pre-fetched in-transaction)
 */
public record RevocationAlertContext(
        UUID certId,
        String host,
        int port,
        UUID orgId,
        String revocationReason,
        String revocationSource,
        Instant revokedAt,
        boolean onHold,
        String severity,
        Map<String, Object> channels
) {
    public boolean isHighSeverity() {
        return "KEY_COMPROMISE".equals(revocationReason) || "CA_COMPROMISE".equals(revocationReason);
    }
}
