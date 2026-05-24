package com.certguard.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "revoked_tokens",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "org_id"}))
@Getter
@NoArgsConstructor
public class RevokedToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_by_user_id")
    private UUID revokedByUserId;

    private String reason;

    public static RevokedToken of(UUID userId, UUID orgId, UUID revokedByUserId,
                                   String reason, Instant expiresAt) {
        RevokedToken t = new RevokedToken();
        t.userId          = userId;
        t.orgId           = orgId;
        t.revokedAt       = Instant.now();
        t.expiresAt       = expiresAt;
        t.revokedByUserId = revokedByUserId;
        t.reason          = reason;
        return t;
    }

    /** Update in-place for upsert (same user+org, newer revocation). */
    public void refresh(UUID newRevokedBy, String newReason, Instant newExpiresAt) {
        this.revokedAt       = Instant.now();
        this.expiresAt       = newExpiresAt;
        this.revokedByUserId = newRevokedBy;
        this.reason          = newReason;
    }
}
