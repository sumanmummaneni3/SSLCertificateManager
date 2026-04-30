package com.certguard.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateOrgProfileRequest {
    @Size(max = 255) private String name;
    @Size(max = 255) private String addressLine1;
    @Size(max = 255) private String addressLine2;
    @Size(max = 100) private String city;
    @Size(max = 100) private String stateProvince;
    @Size(max = 20)  private String postalCode;
    @Size(max = 100) private String country;
    @Size(max = 50)  private String phone;
    @Email @Size(max = 255) private String contactEmail;
    private Boolean isMsp;
}
