package com.certguard.dto.response;

import com.certguard.enums.NetworkScanStatus;
import com.certguard.enums.PortScanProfile;

import java.time.Instant;
import java.util.UUID;

public record NetworkScanResponse(
        UUID id,
        UUID orgId,
        UUID agentId,
        String agentName,
        String cidr,
        PortScanProfile portProfile,
        NetworkScanStatus status,
        Integer hostsTotal,
        int hostsScanned,
        int openPortCount,
        int tlsFoundCount,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {}
