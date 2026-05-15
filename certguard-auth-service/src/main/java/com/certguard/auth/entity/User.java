package com.certguard.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_users",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_auth_users_email", columnNames = "email"),
            @UniqueConstraint(name = "uq_auth_users_provider", columnNames = {"provider_id", "provider_user_id"})
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** e.g. "google", "microsoft", "email" */
    @Column(name = "provider_id", nullable = false, length = 32)
    private String providerId;

    /** The provider's own subject/oid for the user (null for email auth). */
    @Column(name = "provider_user_id", length = 256)
    private String providerUserId;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "name", length = 256)
    private String name;

    /** BCrypt hash — only set for provider_id = 'email'. */
    @Column(name = "password_hash", length = 256)
    private String passwordHash;

    /** Current OAuth access token from the provider. */
    @Column(name = "access_token", length = 2048)
    private String accessToken;

    /** Current OAuth refresh token from the provider. */
    @Column(name = "refresh_token", length = 2048)
    private String refreshToken;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    /** False until explicitly confirmed (email flow uses OTP/magic-link). */
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
