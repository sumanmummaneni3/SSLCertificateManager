package com.certguard.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /api/v1/admin/scanner-pool} — platform-admin, platform-global
 * (not org-scoped). Surfaces the PLATFORM_SCANNER agent fleet and PUBLIC_POOL backlog
 * depth (RFC 0013 §9).
 */
@Data
@Builder
public class ScannerPoolResponse {

    private List<ScannerInfo> scanners;
    private Backlog backlog;

    @Data
    @Builder
    public static class ScannerInfo {
        private UUID id;
        private String name;
        private String status;
        private Instant lastSeenAt;
        private long jobsClaimedLastHour;
        private long totalJobsCompleted;
    }

    @Data
    @Builder
    public static class Backlog {
        private long pendingCount;
        /** Minutes (not seconds) — the shipped UI consumes this exact field name/unit. */
        private long oldestPendingAgeMinutes;
        private long claimedCount;
        private long failedLast24h;
    }
}
