package com.certguard.dto.request;

public record AgentJobReportRequest(
        String status,
        String errorCode,
        String errorDetail,
        String checksumSha256,
        Long bytesWritten
) {
    public static AgentJobReportRequest success() {
        return new AgentJobReportRequest("SUCCESS", null, null, null, null);
    }
}
