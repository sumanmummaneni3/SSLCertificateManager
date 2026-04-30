package com.certguard.dto.response;
import com.certguard.enums.AgentStatus;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
@Data @Builder
public class AgentResponse {
    private UUID id;
    private String name;
    private AgentStatus status;
    private List<String> allowedCidrs;
    private int maxTargets;
    private int currentTargetCount;
    private Instant lastSeenAt;
    private Instant registeredAt;
    private Instant createdAt;
    private String agentKey;
    private String clientCertPem;
}
