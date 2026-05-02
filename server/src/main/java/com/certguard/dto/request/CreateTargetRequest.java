package com.certguard.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class CreateTargetRequest {
    @NotBlank @Size(max = 255)
    private String host;

    @Min(1) @Max(65535)
    private int port = 443;

    @JsonProperty("isPrivate")
    private boolean isPrivate = false;
    private UUID agentId;

    @Size(max = 255)
    private String description;

    /** Optional — groups this target under a Location */
    private UUID locationId;

    /**
     * Notification channel config. Only "email" is dispatched live.
     * Others (sms, whatsapp, slack, teams, psa, service_desk) are stored
     * and shown as read-only in the UI until implemented.
     */
    private Map<String, Object> notificationChannels = new HashMap<>();
}
