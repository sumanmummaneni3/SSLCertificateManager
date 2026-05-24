package com.certguard.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "org_audit")
@Getter
@NoArgsConstructor
public class OrgAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_email", nullable = false, length = 255)
    private String actorEmail;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target_user_id")
    private UUID targetUserId;

    @Column(name = "target_email", length = 255)
    private String targetEmail;

    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    public static OrgAudit of(UUID orgId, UUID actorUserId, String actorEmail,
                               String action, UUID targetUserId, String targetEmail, String reason) {
        OrgAudit a = new OrgAudit();
        a.orgId         = orgId;
        a.actorUserId   = actorUserId;
        a.actorEmail    = actorEmail;
        a.action        = action;
        a.targetUserId  = targetUserId;
        a.targetEmail   = targetEmail;
        a.reason        = reason;
        return a;
    }
}
