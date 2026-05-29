package com.certguard.dto.response;

import com.certguard.enums.AgentJobType;

import java.util.List;
import java.util.UUID;

public record DeliveryJobResponse(
        UUID jobId,
        UUID targetId,
        AgentJobType jobType,
        String packageId,
        String targetLocation,
        String checksumSha256,
        String fileName,
        String commonName,
        List<String> sans
) {}
