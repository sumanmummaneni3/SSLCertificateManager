package com.certguard.entity;

import com.certguard.enums.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "subscriptions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subscription extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false, unique = true)
    private Organization organization;

    /**
     * Maximum number of certificates this org is allowed to have scanned.
     * Default 10 (set on MSP registration). Only PLATFORM_ADMIN may change this.
     */
    @Column(name = "max_certificate_quota", nullable = false)
    @Builder.Default
    private Integer maxCertificateQuota = 10;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "subscription_status")
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.TRIAL;
}
