package com.certguard.dto.response;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;
@Data @Builder
public class RegistrationTokenResponse {
    private UUID tokenId;
    private String agentName;
    private String token;
    private Instant expiresAt;
}
