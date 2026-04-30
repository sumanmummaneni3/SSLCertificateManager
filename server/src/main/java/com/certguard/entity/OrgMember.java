package com.certguard.entity;

import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "org_members",
    uniqueConstraints = @UniqueConstraint(columnNames = {"org_id", "user_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrgMember extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "org_member_role")
    @Builder.Default
    private OrgMemberRole role = OrgMemberRole.VIEWER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by")
    private User invitedBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "invite_status", nullable = false, columnDefinition = "invite_status")
    @Builder.Default
    private InviteStatus inviteStatus = InviteStatus.PENDING;
}
