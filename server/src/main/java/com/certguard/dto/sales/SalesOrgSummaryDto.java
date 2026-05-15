package com.certguard.dto.sales;

import com.certguard.enums.OrgType;
import com.certguard.enums.SubscriptionStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SalesOrgSummaryDto {

    private UUID orgId;
    private String orgName;
    private OrgType orgType;
    private SubscriptionStatus subscriptionStatus;
    private int maxCertificateQuota;
    private Instant archivedAt;
    private Instant createdAt;
}
