package com.certguard.dto.sales;

import com.certguard.enums.OrgType;
import com.certguard.enums.SubscriptionStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SalesOrgDetailDto extends SalesOrgSummaryDto {

    private int targetCount;
    private int agentCount;
    private int memberCount;
    private UUID parentOrgId;
}
