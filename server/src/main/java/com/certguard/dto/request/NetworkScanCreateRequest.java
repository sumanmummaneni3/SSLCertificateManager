package com.certguard.dto.request;

import com.certguard.enums.PortScanProfile;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

/**
 * Request body for POST /api/v1/organizations/{orgId}/network-scans.
 * Validates that cidr matches IPv4 CIDR notation before service-layer checks.
 */
public record NetworkScanCreateRequest(
        @NotNull UUID agentId,

        @NotBlank
        @Pattern(regexp = "^(\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$",
                 message = "cidr must be valid IPv4 CIDR notation, e.g. 192.168.1.0/24")
        String cidr,

        @NotNull PortScanProfile portProfile,

        List<@Min(1) @Max(65535) Integer> customPorts
) {}
