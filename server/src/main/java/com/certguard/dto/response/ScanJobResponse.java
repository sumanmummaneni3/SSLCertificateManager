package com.certguard.dto.response;

import com.certguard.enums.PortScanProfile;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Returned by GET /api/v1/agent/jobs for each pending job.
 *
 * jobType is absent (null) for existing CERTIFICATE_SCAN jobs — the agent treats
 * null as CERTIFICATE_SCAN for backward compatibility (RFC 0011 §3.1).
 *
 * When jobType == "NETWORK_SCAN", the networkScan nested object is populated with
 * the sweep parameters.
 */
@Data
@Builder
public class ScanJobResponse {
    // ── Classic certificate-scan fields ───────────────────────────────────────
    private UUID jobId;
    private UUID targetId;
    private String host;
    private int port;
    private String lastKnownSerialHash;
    private UUID lastCertificateId;

    // ── RFC 0011: job type discriminator ─────────────────────────────────────
    /** Null means CERTIFICATE_SCAN (backward-compatible). */
    private String jobType;

    /** Populated only when jobType == "NETWORK_SCAN". */
    private NetworkScanPayload networkScan;

    @Data
    @Builder
    public static class NetworkScanPayload {
        private UUID networkScanId;
        private String cidr;
        private PortScanProfile portProfile;
        private List<Integer> customPorts;
        private int connectTimeoutMs;
        private int tlsTimeoutMs;
    }
}
