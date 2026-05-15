package com.certguard.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ValidateRequest(@NotBlank String token) {}
