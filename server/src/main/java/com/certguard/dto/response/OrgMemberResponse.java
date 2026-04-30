package com.certguard.dto.response;

import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class OrgMemberResponse {
    private UUID id;
    private UUID userId;
    private String email;
    private String name;
    private OrgMemberRole role;
    private InviteStatus inviteStatus;
    private String invitedByEmail;
    private Instant createdAt;
}
