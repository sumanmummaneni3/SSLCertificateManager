package com.certguard.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_user_sessions",
        indexes = {
            @Index(name = "idx_sessions_token", columnList = "session_token"),
            @Index(name = "idx_sessions_user_id", columnList = "user_id"),
            @Index(name = "idx_sessions_expires_at", columnList = "expires_at")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserSession {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_sessions_user"))
    private User user;

    /** The opaque session token returned to the client (also the JWT jti). */
    @Column(name = "session_token", nullable = false, unique = true, columnDefinition = "TEXT")
    private String sessionToken;

    /** Provider that created this session: google | microsoft | email */
    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    /** Provider's refresh token at session-creation time (encrypted at rest recommended). */
    @Column(name = "refresh_token", length = 2048)
    private String refreshToken;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Timestamp of the last token validation call — used to detect stale sessions. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** IP address at login time — for audit / anomaly detection. */
    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
