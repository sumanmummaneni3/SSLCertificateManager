package com.certguard.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class CreateAgentRequest {

    /**
     * Human-readable agent name. Only alphanumerics, spaces, hyphens, dots, and
     * underscores are allowed (3–64 characters) to prevent injection into generated
     * config file content (BACKEND_REVIEW P2-6).
     */
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9 _.-]{3,64}$",
             message = "agentName must be 3–64 characters: letters, digits, spaces, hyphens, dots, underscores")
    private String agentName;

    /** CIDR ranges this agent is permitted to scan. At least one required. */
    @NotNull
    @Size(min = 1, message = "At least one allowedCidr must be specified")
    private List<String> allowedCidrs = new ArrayList<>();

    /** Maximum number of targets the agent may hold. */
    @Min(1)
    @Max(500)
    private int maxTargets = 50;

    /** Optional location the agent should be associated with. */
    private UUID locationId;
}
