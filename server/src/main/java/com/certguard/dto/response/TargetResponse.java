package com.certguard.dto.response;

import com.certguard.enums.HostType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data @Builder
public class TargetResponse {
    private UUID id;
    private String host;
    private int port;
    private HostType hostType;
    @JsonProperty("isPrivate")
    private boolean isPrivate;
    private String description;
    private boolean enabled;
    private UUID agentId;
    private String agentName;
    private UUID locationId;
    private String locationName;
    private Instant lastScannedAt;
    private String lastErrorMessage;
    private Instant lastErrorAt;
    private Instant createdAt;
    private CertificateSummary latestCertificate;
    private Map<String, Object> notificationChannels;

    /**
     * ISO-8601 instant of the oldest PENDING agent_scan_job for this target, or null
     * when no PENDING job exists. UI uses this for the "scan delayed" hint (>10 min
     * age threshold applied client-side). RFC 0013 §9.
     */
    private Instant pendingScanQueuedAt;
}
