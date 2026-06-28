package com.certguard.entity;

import com.certguard.enums.DeviceClass;
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
 * A device discovered during an anonymous scan.
 * Privacy: no IP address stored. Device is described by port/banner/TLS signals only.
 */
@Entity
@Table(name = "anon_discovered_devices")
@Getter
@Setter
@NoArgsConstructor
public class AnonDiscoveredDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private AnonScanSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subnet_id", nullable = false, updatable = false)
    private AnonDiscoveredSubnet subnet;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "device_class", nullable = false, columnDefinition = "device_class")
    private DeviceClass deviceClass = DeviceClass.UNKNOWN;

    @Column(name = "open_port_count", nullable = false)
    private int openPortCount = 0;

    @Column(name = "tls_port_count", nullable = false)
    private int tlsPortCount = 0;

    /** Open ports as integer[] in PostgreSQL. */
    @Column(name = "open_ports", nullable = false, columnDefinition = "integer[]")
    private int[] openPorts = new int[0];

    /** Banner map as JSON string: http_server, http_title, ssh_version, tls_cn, tls_o. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String banners;

    /** TLS CN values from certs found on this device, for display only. text[] in PostgreSQL. */
    @Column(name = "tls_subjects", columnDefinition = "text[]")
    private String[] tlsSubjects;

    /** Earliest expiry across TLS certs found on this device, for display only. */
    @Column(name = "tls_expiry_min")
    private Instant tlsExpiryMin;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
