package com.certguard.dto.response;
import lombok.Builder;
import lombok.Data;
import java.util.UUID;
@Data @Builder
public class ScanJobResponse {
    private UUID jobId;
    private UUID targetId;
    private String host;
    private int port;
    private String lastKnownSerialHash;
    private UUID lastCertificateId;
}
