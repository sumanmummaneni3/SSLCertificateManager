package com.certguard.entity;

import com.certguard.enums.ScanJobStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "agent_scan_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AgentScanJob extends BaseEntity {

    /**
     * The agent that owns or has claimed this job.
     * Nullable: PUBLIC_POOL jobs start with agent_id = NULL and are stamped on claim.
     * AGENT_PINNED jobs still require an agent at insert time (enforced by service layer
     * and the DB CHECK constraint). RFC 0013 §1.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = true)
    private Agent agent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id", nullable = false)
    private Target target;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "scan_job_status")
    @Builder.Default
    private ScanJobStatus status = ScanJobStatus.PENDING;

    @Column(name = "result_type", length = 10)
    private String resultType;

    /**
     * Last error message from an ERROR result submission.
     * Cleared on successful COMPLETED transition.
     * Reused column from V3 schema — no new column added (RFC 0013 §1).
     */
    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Origin of this scan job: 'SCHEDULED' (system/sweep) or 'USER' (manual force-scan).
     * Read by AgentService.submitResult to select EvaluationMode (RFC 0008 §6.3).
     */
    @Column(name = "trigger_source", nullable = false, length = 16)
    @Builder.Default
    private String triggerSource = "SCHEDULED";

    /**
     * Job kind: 'AGENT_PINNED' (pre-assigned to a specific agent, today's behavior)
     * or 'PUBLIC_POOL' (claimed by any available platform scanner, RFC 0013 §1).
     */
    @Column(name = "job_kind", nullable = false, length = 20)
    @Builder.Default
    private String jobKind = "AGENT_PINNED";

    /**
     * Number of ERROR results submitted for this job.
     * When attempts >= 3 the job is marked FAILED instead of re-queued.
     * RFC 0013 §5.
     */
    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    /** Convenience constant for the pinned-to-agent job kind. */
    public static final String KIND_AGENT_PINNED = "AGENT_PINNED";

    /** Convenience constant for the public pool job kind. */
    public static final String KIND_PUBLIC_POOL  = "PUBLIC_POOL";
}
