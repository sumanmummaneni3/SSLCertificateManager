package com.certguard.dto.response;

import com.certguard.enums.AnonSessionStatus;
import com.certguard.enums.DeviceClass;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only dashboard data returned by GET /api/v1/anon/sessions/{viewToken}.
 * No IP addresses are included — only aggregate display-tier data.
 */
public record AnonSessionDashboardResponse(
        AnonSessionStatus status,
        Instant scanExpiresAt,
        Instant viewExpiresAt,
        SummaryDto summary,
        List<SubnetDto> subnets,
        List<DeviceDto> devices
) {

    public record SummaryDto(
            int subnetCount,
            int deviceCount,
            int tlsFoundCount,
            int routerCount,
            int serverCount
    ) {}

    public record SubnetDto(
            UUID id,
            String cidr,
            int deviceCount,
            int tlsCount
    ) {}

    public record DeviceDto(
            UUID id,
            String subnetCidr,
            DeviceClass deviceClass,
            int[] openPorts,
            Map<String, String> banners,
            List<String> tlsSubjects,
            Instant tlsExpiryMin
    ) {}
}
