package com.certguard.dto.admin;

import com.certguard.enums.OrgType;
import com.certguard.enums.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminOrgDto(
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
        Instant createdAt
) {}
