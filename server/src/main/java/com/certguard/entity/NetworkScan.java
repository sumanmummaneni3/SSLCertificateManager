package com.certguard.entity;

import com.certguard.enums.NetworkScanStatus;
import com.certguard.enums.PortScanProfile;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * One network sweep job: a CIDR × port-profile sweep dispatched to an agent.
 * Org-scoped; org_id is denormalized for query performance per project convention.
 */
@Entity
@Table(name = "network_scans")
@Getter
@Setter
@NoArgsConstructor
public class NetworkScan extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false, updatable = false)
    private Organization organization;

    /** Denormalized org_id for query performance (avoids JOIN in tenant-scoped queries). */
    @Column(name = "org_id", insertable = false, updatable = false)
    private UUID orgId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @Column(nullable = false)
    private String cidr;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "port_profile", nullable = false, columnDefinition = "port_scan_profile")
    private PortScanProfile portProfile;

    /** Null unless portProfile == CUSTOM. Stored as integer[] in PostgreSQL. */
    @Column(name = "custom_ports", columnDefinition = "integer[]")
    private int[] customPorts;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "network_scan_status")
    private NetworkScanStatus status = NetworkScanStatus.PENDING;

    /** Total number of hosts in the CIDR. Set once the sweep starts. */
    @Column(name = "hosts_total")
    private Integer hostsTotal;

    @Column(name = "hosts_scanned", nullable = false)
    private int hostsScanned = 0;

    @Column(name = "open_port_count", nullable = false)
    private int openPortCount = 0;

    @Column(name = "tls_found_count", nullable = false)
    private int tlsFoundCount = 0;

    @Column(name = "error_message")
    private String errorMessage;
}
