package com.certguard.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for POST /api/v1/admin/orgs/{orgId}/transfer/undo (RFC 0010 §4).
 */
public class OrgTransferUndoRequest {

    @NotNull(message = "migrationId is required")
    private UUID migrationId;

    @NotBlank(message = "reason is required")
    private String reason;

    public UUID getMigrationId() { return migrationId; }
    public String getReason()    { return reason; }

    public void setMigrationId(UUID v) { this.migrationId = v; }
    public void setReason(String v)    { this.reason = v; }
}
