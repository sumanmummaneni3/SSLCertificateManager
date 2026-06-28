package com.certguard.dto.request;

import com.certguard.enums.DeviceClass;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Payload pushed by the anonymous agent via POST /api/v1/anon/discovery-results.
 * Privacy: no IP addresses in the device records.
 * Devices are grouped by subnet CIDR and device class only.
 */
public record AnonDiscoveryResultsRequest(
        @NotNull List<SubnetDto> subnets,
        @NotNull List<DeviceDto> devices
) {

    public record SubnetDto(
            @NotBlank String cidr,
            String ifaceName
    ) {}

    public record DeviceDto(
            @NotBlank String subnetCidr,
            DeviceClass deviceClass,
            @NotNull List<@Min(1) @Max(65535) Integer> openPorts,
            int tlsPortCount,
            Map<String, String> banners,
            List<String> tlsSubjects,
            Instant tlsExpiryMin
    ) {}
}
