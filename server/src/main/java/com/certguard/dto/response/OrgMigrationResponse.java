package com.certguard.dto.response;

import java.util.UUID;

/**
 * Response body for org transfer and undo operations (RFC 0010 §4).
 *
 * Returns the updated org (reflecting new parentOrgId) plus a migration summary.
 */
public record OrgMigrationResponse(
        OrgResponse org,
        MigrationSummary migration
) {
    public record MigrationSummary(
            UUID   migrationId,
            String direction,
            int    revokedMemberCount,
            int    restoredMemberCount,
            int    inFlightScanJobCount
    ) {}
}
