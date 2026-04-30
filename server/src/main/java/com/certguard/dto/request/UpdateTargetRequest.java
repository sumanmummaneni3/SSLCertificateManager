package com.certguard.dto.request;
import lombok.Data;
import java.util.UUID;
@Data
public class UpdateTargetRequest {
    private String host;
    private Integer port;
    private Boolean isPrivate;
    private Boolean enabled;
    private String description;
    private UUID agentId;
}
