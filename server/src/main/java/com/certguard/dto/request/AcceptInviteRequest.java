package com.certguard.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AcceptInviteRequest {
    @NotBlank @Email
    private String email;

    /** The raw invite token from the email link */
    @NotBlank
    private String token;

    /** 6-digit OTP from the follow-up email */
    @NotBlank
    private String otp;
}
