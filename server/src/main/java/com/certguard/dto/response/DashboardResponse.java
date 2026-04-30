package com.certguard.dto.response;
import lombok.Builder;
import lombok.Data;
@Data @Builder
public class DashboardResponse {
    private long totalTargets;
    private long totalCertificates;
    private long valid;
    private long expiring;
    private long expired;
    private long unreachable;
}
