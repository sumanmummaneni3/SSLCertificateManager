package com.certguard.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class MspDashboardResponse {
    private UUID mspOrgId;
    private long childOrgCount;
    private long totalTargets;
    private long totalAgents;
    private long valid;
    private long expiring;
    private long expired;
    private long unreachable;
    private List<MspChildOrgStat> perOrg;
}
