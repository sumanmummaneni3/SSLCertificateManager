package com.certguard.util;

import com.certguard.dto.response.ScanSource;
import com.certguard.entity.Agent;
import com.certguard.entity.AgentScanJob;
import com.certguard.entity.CertificateRecord;
import com.certguard.enums.AgentType;
import com.certguard.enums.ScanJobStatus;
import com.certguard.enums.ScanSourceType;

/**
 * Shared mapping from persisted scan provenance to the {@code scanSource} API shape
 * (RFC 0013 §9). Stateless — used by CertificateService, TargetService.
 */
public final class ScanSourceMapper {

    /** Job result_type stamped by PublicScanFallbackScheduler for HYBRID in-process fallback. */
    public static final String RESULT_TYPE_DIRECT_FALLBACK = "DIRECT_FALLBACK";

    private ScanSourceMapper() {}

    /**
     * Maps a {@link CertificateRecord}'s persisted {@code scanSourceType} to the API shape.
     *
     * @return {@code null} when the record has no recorded provenance (legacy row,
     *         predates the scan_source_type column) — callers must omit the field,
     *         not default it.
     */
    public static ScanSource fromCertificateRecord(CertificateRecord cert) {
        if (cert == null) return null;
        ScanSourceType type = cert.getScanSourceType();
        if (type == null) return null; // legacy — omit, do not guess

        if (type == ScanSourceType.CLOUD_SCANNER) {
            // Tenant-boundary rule: never expose the claiming scanner's identity.
            return ScanSource.builder().type(ScanSource.Type.CLOUD_SCANNER).build();
        }

        Agent agent = cert.getScannedByAgent();
        return ScanSource.builder()
                .type(ScanSource.Type.CUSTOMER_AGENT)
                .agentId(agent != null ? agent.getId() : null)
                .agentName(agent != null ? agent.getName() : null)
                .build();
    }

    /**
     * Maps a completed {@link AgentScanJob} to the API shape, for scan-status responses.
     *
     * @return {@code null} when the job isn't COMPLETED yet, or has no claiming agent
     *         recorded (shouldn't normally happen for a COMPLETED non-fallback job).
     */
    public static ScanSource fromCompletedJob(AgentScanJob job) {
        if (job == null || job.getStatus() != ScanJobStatus.COMPLETED) return null;

        if (RESULT_TYPE_DIRECT_FALLBACK.equals(job.getResultType())) {
            return ScanSource.builder().type(ScanSource.Type.CLOUD_SCANNER).build();
        }

        Agent agent = job.getAgent();
        if (agent == null) return null;

        if (agent.getAgentType() == AgentType.PLATFORM_SCANNER) {
            return ScanSource.builder().type(ScanSource.Type.CLOUD_SCANNER).build();
        }

        return ScanSource.builder()
                .type(ScanSource.Type.CUSTOMER_AGENT)
                .agentId(agent.getId())
                .agentName(agent.getName())
                .build();
    }
}
