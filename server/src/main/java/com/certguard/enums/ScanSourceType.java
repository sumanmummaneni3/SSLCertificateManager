package com.certguard.enums;

/**
 * Persisted provenance of the scan that produced a {@code CertificateRecord}.
 *
 * CLOUD_SCANNER   — scanned by a PLATFORM_SCANNER agent (PUBLIC_POOL job), or by the
 *                   in-process direct/HYBRID-fallback scanner (both are "the cloud").
 * CUSTOMER_AGENT  — scanned by a CUSTOMER agent via an AGENT_PINNED job.
 *
 * Null on the entity means "not recorded" (row predates this column) — the API layer
 * omits the {@code scanSource} field entirely in that case rather than guessing.
 * RFC 0013 §9.
 */
public enum ScanSourceType {
    CLOUD_SCANNER,
    CUSTOMER_AGENT
}
