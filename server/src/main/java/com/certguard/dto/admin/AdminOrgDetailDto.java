package com.certguard.dto.admin;

import com.certguard.enums.OrgType;
import com.certguard.enums.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminOrgDetailDto(
        UUID id,
        String name,
        String slug,
        OrgType orgType,
        UUID parentOrgId,
        long memberCount,
        long targetCount,
        long agentCount,
        String subscriptionTier,
        SubscriptionStatus subscriptionStatus,
        Instant createdAt,
        // Address / contact fields
        String addressLine1,
        String addressLine2,
        String city,
        String stateProvince,
        String postalCode,
        String country,
        String phone,
        String contactEmail
) {}
