package com.certguard.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Per-target or org-default notification policy row (RFC 0008 §3).
 *
 * Resolution order in {@link com.certguard.service.ExpiryEvaluationService}:
 *   1. Row where target_id = this target's id  (per-target override)
 *   2. Row where org_id = this target's org AND target_id IS NULL  (org default)
 *   3. App-yml fallback (warning-days / critical-days / dedup-hours)
 *
 * The two partial unique indexes in V33 enforce:
 *   - At most one org-default row per org (target_id IS NULL).
 *   - At most one override row per target (target_id IS NOT NULL).
 */
@Entity
@Table(name = "notification_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationSettings extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    /** Null → this row is the org-level default. Non-null → per-target override. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id")
    private Target target;

    /** Master kill-switch: when false, no alerts are sent for the resolved scope. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** Certificates expiring within this many days enter the alert window. */
    @Column(name = "warning_days", nullable = false)
    @Builder.Default
    private Integer warningDays = 30;

    /** Certificates expiring within this many days are rated CRITICAL. */
    @Column(name = "critical_days", nullable = false)
    @Builder.Default
    private Integer criticalDays = 7;

    /** Minimum hours between repeat alerts for the same certificate record. */
    @Column(name = "dedup_hours", nullable = false)
    @Builder.Default
    private Integer dedupHours = 23;
}
