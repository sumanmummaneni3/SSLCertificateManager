package com.certguard.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A subnet discovered via local NIC inspection by an anonymous agent.
 * Only RFC1918 CIDRs are permitted (enforced by AnonScanService).
 */
@Entity
@Table(name = "anon_discovered_subnets")
@Getter
@Setter
@NoArgsConstructor
public class AnonDiscoveredSubnet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private AnonScanSession session;

    @Column(nullable = false)
    private String cidr;

    @Column(name = "iface_name")
    private String ifaceName;

    @Column(nullable = false)
    private String source = "LOCAL_NIC";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
