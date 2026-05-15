package com.certguard.dto.sales;

import com.certguard.enums.SubscriptionStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSubscriptionStatusRequest {

    @NotNull
    private SubscriptionStatus status;

    private String reason;
}
