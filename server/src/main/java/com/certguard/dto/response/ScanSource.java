package com.certguard.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Scan provenance surfaced on certificate / target / scan-status responses
 * (RFC 0013 §9 — ratified frontend contract).
 *
 * <p>{@code agentId}/{@code agentName} are ALWAYS null when {@code type == CLOUD_SCANNER}
 * — the identity of the platform scanner that happened to claim a pool job must never
 * leak into a customer tenant's response (tenant-boundary rule).
 *
 * <p>Callers should omit this object entirely (leave the containing field null) for
 * legacy records with no recorded provenance — see {@code ScanSourceMapper}.
 */
@Data
@Builder
public class ScanSource {

    public enum Type {
        CLOUD_SCANNER,
        CUSTOMER_AGENT
    }

    private Type type;
    private UUID agentId;
    private String agentName;
}
