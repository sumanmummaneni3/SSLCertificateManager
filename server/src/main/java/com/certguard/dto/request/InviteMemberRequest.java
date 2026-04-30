package com.certguard.dto.request;

import com.certguard.enums.OrgMemberRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InviteMemberRequest {
    @NotBlank @Email
    private String email;

    @NotNull
    private OrgMemberRole role;
}
