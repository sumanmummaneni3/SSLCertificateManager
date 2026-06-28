package com.certguard.dto.request;

import com.certguard.enums.PortScanProfile;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

/**
 * Request body for POST /api/v1/organizations/{orgId}/network-scans.
 *
 * <p>{@code cidr} is optional since RFC 0012. When {@code null} the service fans out
 * across all subnets the agent has self-reported via NicSubnetDiscovery (zero-CIDR
 * enrollment). When provided it must be valid IPv4 CIDR notation and must fall within
 * an RFC 1918 range.
 */
public record NetworkScanCreateRequest(
        @NotNull UUID agentId,

        /**
         * Target CIDR to sweep. Null means "scan all agent-discovered subnets".
         * When present, must be valid IPv4 CIDR (pattern check; not-blank enforced by pattern).
         */
        @Pattern(regexp = "^(\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$",
                 message = "cidr must be valid IPv4 CIDR notation, e.g. 192.168.1.0/24")
        String cidr,

        @NotNull PortScanProfile portProfile,

        List<@Min(1) @Max(65535) Integer> customPorts
) {}
