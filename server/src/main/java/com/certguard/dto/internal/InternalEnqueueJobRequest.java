package com.certguard.dto.internal;

import com.certguard.enums.AgentJobType;

import java.util.Map;
import java.util.UUID;

public record InternalEnqueueJobRequest(
        UUID agentId,
        UUID orgId,
        UUID targetId,
        UUID renewalId,
        AgentJobType jobType,
        Map<String, Object> payload,
        String dedupKey
) {}
