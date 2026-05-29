package com.certguard.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AgentCsrSubmitRequest(
        @NotBlank String csrPem
) {}
