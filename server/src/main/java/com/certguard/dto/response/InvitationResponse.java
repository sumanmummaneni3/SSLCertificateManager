package com.certguard.dto.response;

import com.certguard.enums.OrgMemberRole;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class InvitationResponse {
    private UUID id;
    private String email;
    private OrgMemberRole role;
    private Instant expiresAt;
    private Instant createdAt;
    /** Raw token — returned ONCE at creation, never again */
    private String token;
}
