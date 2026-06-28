package com.certguard.dto.request;

import com.certguard.enums.DeviceClass;
import com.certguard.enums.EndpointPortState;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Batch of host/port scan results submitted by an agent via
 * POST /api/v1/agent/network-results.
 * HMAC is computed over "{networkScanId}:{chunkIndex}:{hostCount}:{timestamp}".
 */
public record AgentNetworkResultsBatch(
        @NotNull UUID networkScanId,
        int chunkIndex,
        int totalChunks,
        long timestamp,
        @NotBlank String hmac,
        @NotNull List<HostResult> hosts
) {

    public record HostResult(
            @NotBlank String ip,
            @NotNull List<PortResult> ports
    ) {}

    public record PortResult(
            @Min(1) @Max(65535) int port,
            @NotNull EndpointPortState state,
            /** Base64-DER encoded chain, leaf-first. Present only when state == OPEN_TLS. */
            List<String> chainB64,
            DeviceClass deviceClass,
            Map<String, String> banners
    ) {}
}
