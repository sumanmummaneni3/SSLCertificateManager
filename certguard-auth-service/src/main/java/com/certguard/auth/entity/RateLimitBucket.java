package com.certguard.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Tracks per-IP authentication attempt counts for distributed rate-limiting. */
@Entity
@Table(name = "auth_rate_limit_buckets",
        indexes = @Index(name = "idx_rl_key", columnList = "bucket_key"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RateLimitBucket {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** IP address or "email:<addr>" key. */
    @Column(name = "bucket_key", nullable = false, unique = true, length = 256)
    private String bucketKey;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
