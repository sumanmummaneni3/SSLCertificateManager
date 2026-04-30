package com.certguard.dto.response;

import com.certguard.enums.LocationProvider;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data @Builder
public class LocationResponse {
    private UUID id;
    private UUID orgId;
    private String name;
    private LocationProvider provider;
    private String geoRegion;
    private String cloudRegion;
    private String address;
    private Map<String, String> customFields;
    private int targetCount;
    private Instant createdAt;
    private Instant updatedAt;
}
