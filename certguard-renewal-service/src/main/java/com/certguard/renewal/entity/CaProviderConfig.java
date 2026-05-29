package com.certguard.renewal.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "ca_provider_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "provider_type", nullable = false, length = 32)
    private String providerType;

    @Column(name = "is_platform_default", nullable = false)
    @Builder.Default
    private boolean platformDefault = false;

    @Column(length = 128)
    private String label;

    @Column(name = "credentials_enc", columnDefinition = "TEXT")
    private String credentialsEnc;
}
