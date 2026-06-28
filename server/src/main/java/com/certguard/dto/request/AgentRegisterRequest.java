package com.certguard.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;
@Data
public class AgentRegisterRequest {
    @NotBlank private String registrationToken;
    @NotBlank @Size(max = 100) private String agentName;
    /** Optional since RFC 0012 — agent self-reports discovered subnets at registration. */
    private List<String> allowedCidrs = new ArrayList<>();
    /** Subnets auto-discovered by NicSubnetDiscovery on the agent host (RFC 0012). */
    private List<String> discoveredSubnets = new ArrayList<>();
    @Min(1) @Max(500) private int maxTargets = 50;
    private String agentVersion;
}
