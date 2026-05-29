package com.certguard.renewal.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "certificate_packages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CertificatePackage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "renewal_id", nullable = false)
    private UUID renewalId;

    @Column(name = "storage_path", nullable = false, length = 1024)
    private String storagePath;

    @Column(name = "file_name", nullable = false, length = 256)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 128)
    @Builder.Default
    private String contentType = "application/x-pem-file";

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;
}
