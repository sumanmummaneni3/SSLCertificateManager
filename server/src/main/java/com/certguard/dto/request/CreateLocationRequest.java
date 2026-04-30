package com.certguard.dto.request;

import com.certguard.enums.LocationProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class CreateLocationRequest {
    @NotBlank @Size(max = 100)
    private String name;

    @NotNull
    private LocationProvider provider;

    @Size(max = 100) private String geoRegion;
    @Size(max = 100) private String cloudRegion;
    @Size(max = 500) private String address;

    private Map<String, String> customFields = new HashMap<>();
}
