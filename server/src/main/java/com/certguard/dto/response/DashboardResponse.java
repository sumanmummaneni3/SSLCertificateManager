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
    /** RFC 0009: count of certificates with confirmed REVOKED status. */
    private long revoked;
    /** RFC 0009: count of public certificates with untrusted/invalid chains (INVALID status). */
    private long invalid;
}
