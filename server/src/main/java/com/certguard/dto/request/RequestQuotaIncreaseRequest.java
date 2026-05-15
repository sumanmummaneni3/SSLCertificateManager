package com.certguard.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RequestQuotaIncreaseRequest(
        @NotNull @Min(11) int requestedQuota,
        @Size(max = 500) String reason
) {}
