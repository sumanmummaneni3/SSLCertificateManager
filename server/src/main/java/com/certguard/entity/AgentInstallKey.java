package com.certguard.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_install_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentInstallKey extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    /**
     * Denormalised org_id — mirrors the convention used across all child tables
     * (agent_scan_jobs, certificate_records) to avoid joins for tenant-scoping.
     */
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "kdf_salt", nullable = false)
    private byte[] kdfSalt;

    @Column(name = "kdf_memory_kib", nullable = false)
    private int kdfMemoryKib;

    @Column(name = "kdf_iterations", nullable = false)
    private int kdfIterations;

    @Column(name = "kdf_parallelism", nullable = false)
    private int kdfParallelism;

    /** BCrypt hash of the plaintext install key shown once in the UI. */
    @Column(name = "install_key_hash", nullable = false, length = 255)
    private String installKeyHash;

    /** SHA-256 hex of the one-time download URL token. */
    @Column(name = "bundle_download_token_hash", nullable = false, unique = true, length = 255)
    private String bundleDownloadTokenHash;

    /** AES-256-GCM encrypted config blob produced by AgentBundleCrypto.sealPayload(). */
    @Column(name = "sealed_payload", nullable = false)
    private byte[] sealedPayload;

    /** Set once the bundle ZIP has been downloaded; used to reject replays. */
    @Column(name = "bundle_downloaded_at")
    private Instant bundleDownloadedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_by")
    private UUID createdBy;
}
