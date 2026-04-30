package com.certguard.dto.response;

import com.certguard.enums.HostType;
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
    private boolean isPrivate;
    private String description;
    private boolean enabled;
    private UUID agentId;
    private String agentName;
    private UUID locationId;
    private String locationName;
    private Instant lastScannedAt;
    private Instant createdAt;
    private CertificateSummary latestCertificate;
    private Map<String, Object> notificationChannels;
}
