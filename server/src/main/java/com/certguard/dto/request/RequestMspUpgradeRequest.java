package com.certguard.dto.request;

import jakarta.validation.constraints.Size;

public record RequestMspUpgradeRequest(
        @Size(max = 500) String reason
) {}
