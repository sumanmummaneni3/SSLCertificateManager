package com.certguard.dto.request;

import com.certguard.enums.OrgType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompleteOnboardingRequest {

    @NotBlank
    @Size(max = 255)
    private String orgName;

    @NotNull
    private OrgType orgType;

    @Email
    @Size(max = 255)
    private String contactEmail;

    @Size(max = 100)
    private String country;
}
