package com.certguard.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailRegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        String name
) {}
