package com.certguard.dto.sales;

import com.certguard.enums.SubscriptionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesSubscriptionDto {

    private UUID subscriptionId;
    private UUID orgId;
    private SubscriptionStatus status;
    private int maxCertificateQuota;
    private Instant updatedAt;
}
