package com.certguard.dto.response;

import com.certguard.enums.ScanJobStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class ScanStatusResponse {
    private UUID jobId;
    private UUID targetId;
    private ScanJobStatus status;
    private String resultType;
    private String errorMsg;
    private Instant createdAt;
    private Instant claimedAt;
    private Instant completedAt;

    /**
     * Scan provenance (RFC 0013 §9) — only populated once the job is COMPLETED.
     * Omitted entirely (not even {@code null}) while PENDING/CLAIMED/FAILED, or for
     * legacy jobs with no recoverable provenance — see {@code ScanSourceMapper}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ScanSource scanSource;
}
