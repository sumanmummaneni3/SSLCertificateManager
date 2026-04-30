package com.certguard.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "org_notification_channels")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrgNotificationChannel extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(name = "channel_type", nullable = false)
    private String channelType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;
}
