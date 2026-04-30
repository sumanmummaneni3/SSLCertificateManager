package com.certguard.dto.response;

import com.certguard.enums.OrgType;
import com.certguard.enums.SubscriptionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class OrgResponse {
    private UUID id;
    private String name;
    private String slug;
    private OrgType orgType;
    private UUID parentOrgId;

    // Contact profile
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String stateProvince;
    private String postalCode;
    private String country;
    private String phone;
    private String contactEmail;

    /** Max certificates this org may scan. Read-only for org users; writable by PLATFORM_ADMIN. */
    private int maxCertificateQuota;
    private SubscriptionStatus status;
    private Instant createdAt;
}
