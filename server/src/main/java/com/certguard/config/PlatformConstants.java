package com.certguard.config;

import java.util.UUID;

/**
 * Well-known platform constants shared across services.
 *
 * These values MUST match the seeds in the V41 Flyway migration.
 */
public final class PlatformConstants {

    private PlatformConstants() {}

    /**
     * Reserved organization UUID for platform-operated scanner agents.
     *
     * <p>Seeded by V41__scanner_pool.sql. Platform agents belong to this org so
     * they remain invisible to customer org queries (org-scoped list endpoints
     * filter by the caller's org_id). RFC 0013 §1.
     */
    public static final UUID PLATFORM_ORG_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000000");
}
