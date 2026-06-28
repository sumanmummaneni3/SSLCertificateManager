package com.certguard.entity;

import com.certguard.enums.AnonSessionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * An anonymous free-tier scan session (RFC 0011 Part B).
 *
 * Does NOT extend BaseEntity — it has no org FK and manages its own timestamps.
 * Privacy: no client IP, no MAC stored. Only token hashes are persisted.
 */
@Entity
@Table(name = "anon_scan_sessions")
@Getter
@Setter
@NoArgsConstructor
public class AnonScanSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** SHA-256 hex of the raw scanToken. Never store the raw token. */
    @Column(name = "scan_token_hash", nullable = false, unique = true, length = 64)
    private String scanTokenHash;

    /** SHA-256 hex of the raw viewToken. Never store the raw token. */
    @Column(name = "view_token_hash", nullable = false, unique = true, length = 64)
    private String viewTokenHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "anon_session_status")
    private AnonSessionStatus status = AnonSessionStatus.ACTIVE;

    @Column(name = "scan_expires_at", nullable = false)
    private Instant scanExpiresAt;

    @Column(name = "view_expires_at", nullable = false)
    private Instant viewExpiresAt;

    /** Set when the user claims this session into an org (status → CLAIMED). */
    @Column(name = "claimed_by_org_id")
    private UUID claimedByOrgId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "subnet_count", nullable = false)
    private int subnetCount = 0;

    @Column(name = "device_count", nullable = false)
    private int deviceCount = 0;

    @Column(name = "tls_found_count", nullable = false)
    private int tlsFoundCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
