package com.certguard.dto.response;

import com.certguard.enums.CertStatus;
import com.certguard.enums.DeviceClass;
import com.certguard.enums.EndpointPortState;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DiscoveredEndpointResponse(
        UUID id,
        UUID networkScanId,
        String ip,
        int port,
        EndpointPortState state,
        DeviceClass deviceClass,
        Map<String, String> banners,
        UUID certRecordId,
        String tlsSubjectCn,
        Instant tlsNotAfter,
        CertStatus tlsCertStatus,
        Instant createdAt
) {}
