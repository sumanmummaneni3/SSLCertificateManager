package com.certguard.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Request body for POST /api/v1/admin/orgs/{orgId}/transfer (RFC 0010 §4).
 */
public class OrgTransferRequest {

    private UUID targetMspOrgId;

    /** Optional but recommended — stale-email / double-submit guard. */
    private UUID expectedSourceMspId;

    @NotBlank(message = "reason is required")
    private String reason;

    /** Optional — out-of-band email/ticket reference from the manual process. */
    private String referenceTicket;

    public UUID getTargetMspOrgId()      { return targetMspOrgId; }
    public UUID getExpectedSourceMspId() { return expectedSourceMspId; }
    public String getReason()            { return reason; }
    public String getReferenceTicket()   { return referenceTicket; }

    public void setTargetMspOrgId(UUID v)      { this.targetMspOrgId = v; }
    public void setExpectedSourceMspId(UUID v) { this.expectedSourceMspId = v; }
    public void setReason(String v)            { this.reason = v; }
    public void setReferenceTicket(String v)   { this.referenceTicket = v; }
}
