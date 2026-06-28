package com.certguard.entity;

import com.certguard.enums.CertStatus;
import com.certguard.enums.DeviceClass;
import com.certguard.enums.EndpointPortState;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A single IP:port discovered during a network sweep.
 * org_id is denormalized for query performance per project convention.
 * cert_record_id is set only when state == OPEN_TLS and a certificate was found.
 */
@Entity
@Table(name = "discovered_endpoints")
@Getter
@Setter
@NoArgsConstructor
public class DiscoveredEndpoint extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "network_scan_id", nullable = false, updatable = false)
    private NetworkScan networkScan;

    /** Denormalized for query performance. Set on insert, never mutated. */
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** IPv4 address stored as INET in PostgreSQL. */
    @Column(nullable = false, columnDefinition = "inet")
    private String ip;

    @Column(nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "endpoint_port_state")
    private EndpointPortState state;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "device_class", columnDefinition = "device_class")
    private DeviceClass deviceClass = DeviceClass.UNKNOWN;

    /**
     * Banner map as a JSON string (http_server, http_title, ssh_version, tls_cn, etc.).
     * Parse with ObjectMapper where needed; stored as JSONB.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String banners;

    /** Set when state == OPEN_TLS and the cert was persisted as a CertificateRecord. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cert_record_id")
    private CertificateRecord certRecord;

    @Column(name = "tls_subject_cn")
    private String tlsSubjectCn;

    @Column(name = "tls_not_after")
    private Instant tlsNotAfter;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "tls_cert_status", columnDefinition = "cert_status")
    private CertStatus tlsCertStatus;
}
