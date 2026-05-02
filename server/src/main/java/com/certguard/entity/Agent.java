package com.certguard.entity;

import com.certguard.config.JsonListConverter;
import com.certguard.enums.AgentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "agents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Agent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "agent_key_hash", nullable = false)
    private String agentKeyHash;

    @Column(name = "client_cert_fingerprint")
    private String clientCertFingerprint;

    @Column(name = "client_cert_pem", columnDefinition = "TEXT")
    private String clientCertPem;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_cidrs", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> allowedCidrs = new ArrayList<>();

    @Column(name = "max_targets", nullable = false)
    @Builder.Default
    private Integer maxTargets = 50;

    @Column(name = "current_target_count", nullable = false)
    @Builder.Default
    private Integer currentTargetCount = 0;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "agent_status")
    @Builder.Default
    private AgentStatus status = AgentStatus.PENDING;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "last_offline_alert_sent_at")
    private Instant lastOfflineAlertSentAt;

    @OneToMany(mappedBy = "agent", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Target> targets = new ArrayList<>();
}
