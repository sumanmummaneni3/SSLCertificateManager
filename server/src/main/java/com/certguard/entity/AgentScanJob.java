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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
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

    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
