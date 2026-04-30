package com.certguard.entity;

import com.certguard.enums.HostType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity @Table(name = "targets")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Target extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    @Builder.Default
    private Integer port = 443;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "host_type", nullable = false, columnDefinition = "host_type")
    @Builder.Default
    private HostType hostType = HostType.DOMAIN;

    @Column(name = "is_private", nullable = false)
    @Builder.Default
    private Boolean isPrivate = false;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "last_scanned_at")
    private Instant lastScannedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    // ── V5: Location grouping ─────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    // ── V6: Notification channels ─────────────────────────────────────────
    /**
     * JSONB notification channel config. Only "email" is live.
     * SMS, WhatsApp, Slack, Teams, PSA, ServiceDesk are stored but
     * displayed as read-only in the UI until implemented.
     * Structure: { "email": {"enabled": true, "addresses": ["ops@co.com"]}, ... }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_channels", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> notificationChannels = new HashMap<>();

    @OneToMany(mappedBy = "target", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<CertificateRecord> certificates = new ArrayList<>();
}
