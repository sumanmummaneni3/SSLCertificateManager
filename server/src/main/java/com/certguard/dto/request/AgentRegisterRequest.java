package com.certguard.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;
@Data
public class AgentRegisterRequest {
    @NotBlank private String registrationToken;
    @NotBlank @Size(max = 100) private String agentName;
    @NotNull @Size(min = 1) private List<String> allowedCidrs;
    @Min(1) @Max(500) private int maxTargets = 50;
    private String agentVersion;
}
