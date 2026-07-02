package com.certguard.enums;

/**
 * Controls the public-target scan strategy (RFC 0013 §7).
 *
 * DIRECT  (default at merge) — in-process {@code SslScannerService} handles all public scans.
 *                              Pool code is present but inert. Safe rollback point.
 *
 * HYBRID               — public targets are enqueued as PUBLIC_POOL jobs. If a job remains
 *                        PENDING for more than 10 minutes (no scanner claimed it),
 *                        {@code PublicScanFallbackScheduler} executes the scan in-process
 *                        and marks the job COMPLETED with result_source = DIRECT_FALLBACK.
 *
 * POOL   (end state)   — in-process scanner disabled entirely; pool only. The direct
 *                        {@code SslScannerService} thread pool is not started.
 */
public enum ScanningMode {
    DIRECT,
    HYBRID,
    POOL
}
